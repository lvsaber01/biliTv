package com.lvsaber.bilitv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/** SQLite is accessed on one worker. Cards only read the current account's memory snapshot. */
class ProgressRepository private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: ProgressRepository? = null
        fun get(context: Context): ProgressRepository = instance ?: synchronized(this) {
            instance ?: ProgressRepository(context.applicationContext).also { instance = it }
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(128),
        ThreadFactory { Thread(it, "bili-progress") }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val database = Database(context)
    private val records = mutableMapOf<String, WatchProgress>()
    private val pgcRecords = mutableMapOf<Long, PgcProgress>()
    @Volatile var account: String = "guest"
        private set
    @Volatile var revision = 0L
        private set
    private var refreshGeneration = 0

    @Synchronized fun find(bvid: String, cid: Long? = null): WatchProgress? = records.values
        .filter { it.video.bvid == bvid && (cid == null || it.cid == cid) }.maxByOrNull { it.updatedAt }
    @Synchronized fun recent(): List<WatchProgress> = records.values.groupBy { it.video.bvid }
        .values.mapNotNull { list -> list.maxByOrNull { it.updatedAt } }.sortedByDescending { it.updatedAt }

    @Synchronized fun findPgc(episodeId: Long): PgcProgress? = pgcRecords[episodeId]
    @Synchronized fun recentPgc(): List<PgcProgress> = pgcRecords.values.sortedByDescending { it.updatedAt }

    fun refreshAccount(onReady: (Boolean) -> Unit = {}) {
        val generation = ++refreshGeneration
        submit {
            val cookie = CredentialStore(context).readCookie()
            val next = AccountIdentity.fromCookie(cookie)
            val loaded = mutableMapOf<String, WatchProgress>()
            val loadedPgc = mutableMapOf<Long, PgcProgress>()
            runCatching {
                database.readableDatabase.query("progress", arrayOf("payload"), "account=?", arrayOf(next), null, null, "updated DESC", "5000").use { cursor ->
                    while (cursor.moveToNext()) runCatching { decode(cursor.getString(0)) }.getOrNull()?.let { loaded[key(it)] = it }
                }
            }
            runCatching {
                database.readableDatabase.query("pgc_progress", arrayOf("payload"), "account=?", arrayOf(next), null, null, "updated DESC", "5000").use { cursor ->
                    while (cursor.moveToNext()) runCatching { decodePgc(cursor.getString(0)) }.getOrNull()?.let { loadedPgc[it.playable.episodeId] = it }
                }
            }
            main.post {
                if (generation != refreshGeneration) return@post
                val changed = account != next
                synchronized(this) {
                    // Preserve live checkpoints written while the database was being read.
                    if (!changed) records.forEach { (key, record) -> loaded[key] = WatchProgress.newer(loaded[key], record) }
                    if (!changed) pgcRecords.forEach { (key, record) -> loadedPgc[key] = newerPgc(loadedPgc[key], record) }
                    records.clear(); records.putAll(loaded)
                    pgcRecords.clear(); pgcRecords.putAll(loadedPgc)
                    account = next; revision++
                }
                onReady(changed)
            }
        }
    }

    fun merge(entries: List<WatchProgress>, expectedAccount: String = account) {
        if (account != expectedAccount) return
        entries.forEach { save(it, expectedAccount) }
    }

    fun save(progress: WatchProgress, expectedAccount: String = account) {
        if (progress.video.bvid.isBlank() || progress.cid <= 0 || account != expectedAccount) return
        val accepted = synchronized(this) {
            val key = key(progress)
            val winner = WatchProgress.newer(records[key], progress)
            if (winner !== progress) false else { records[key] = progress; revision++; true }
        }
        if (!accepted) return
        submit {
            val values = ContentValues().apply {
                put("account", expectedAccount); put("bvid", progress.video.bvid); put("cid", progress.cid)
                put("updated", progress.updatedAt); put("payload", encode(progress))
            }
            database.writableDatabase.insertWithOnConflict("progress", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun mergePgc(entries: List<PgcProgress>, expectedAccount: String = account) {
        if (account != expectedAccount) return
        entries.forEach { savePgc(it, expectedAccount) }
    }

    fun savePgc(progress: PgcProgress, expectedAccount: String = account) {
        val ref = progress.playable
        if (ref.episodeId <= 0 || ref.seasonId <= 0 || ref.cid <= 0 || account != expectedAccount) return
        val accepted = synchronized(this) {
            val winner = newerPgc(pgcRecords[ref.episodeId], progress)
            if (winner !== progress) false else { pgcRecords[ref.episodeId] = progress; revision++; true }
        }
        if (!accepted) return
        submit {
            val values = ContentValues().apply {
                put("account", expectedAccount); put("episode_id", ref.episodeId)
                put("updated", progress.updatedAt); put("payload", encodePgc(progress))
            }
            database.writableDatabase.insertWithOnConflict("pgc_progress", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun newerPgc(local: PgcProgress?, incoming: PgcProgress): PgcProgress =
        if (local != null && local.updatedAt >= incoming.updatedAt) local else incoming

    private fun submit(action: () -> Unit) {
        try { worker.execute { runCatching(action) } } catch (_: RejectedExecutionException) { /* The memory checkpoint survives; next periodic save retries. */ }
    }
    private fun key(record: WatchProgress) = "${record.video.bvid}/${record.cid}"
    private fun encode(p: WatchProgress): String = JSONObject().apply {
        put("bvid", p.video.bvid); put("aid", p.video.aid); put("title", p.video.title)
        put("cover", p.video.coverUrl); put("author", p.video.author); put("duration", p.video.duration)
        put("cid", p.cid); put("part", p.part); put("position", p.positionMs); put("length", p.durationMs)
        put("updated", p.updatedAt); put("completed", p.completed)
    }.toString()
    private fun decode(payload: String): WatchProgress {
        val j = JSONObject(payload)
        val video = VideoSummary(j.getString("bvid"), j.optLong("aid"), j.getLong("cid"), j.optString("title"),
            j.optString("cover"), j.optString("author"), j.optString("duration"), "", 0, 0, 0)
        return WatchProgress(video, video.cid, j.optInt("part", 1), j.optLong("position"), j.optLong("length"), j.optLong("updated"), j.optBoolean("completed"))
    }
    private fun encodePgc(p: PgcProgress): String = JSONObject().apply {
        val r = p.playable
        put("seasonId", r.seasonId); put("episodeId", r.episodeId); put("cid", r.cid)
        put("aid", r.aid); put("bvid", r.bvid); put("seasonTitle", r.seasonTitle)
        put("episodeTitle", r.episodeTitle); put("cover", r.coverUrl); put("category", r.category.seasonType)
        put("areas", org.json.JSONArray(r.areas))
        put("position", p.positionMs); put("duration", p.durationMs); put("updated", p.updatedAt); put("completed", p.completed)
    }.toString()

    private fun decodePgc(payload: String): PgcProgress {
        val j = JSONObject(payload)
        val category = PgcCategory.fromSeasonType(j.optInt("category")) ?: PgcCategory.JAPANESE_ANIME
        val areasArray = j.optJSONArray("areas")
        val areas = areasArray?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        val ref = PgcPlayableRef(j.getLong("seasonId"), j.getLong("episodeId"), j.getLong("cid"),
            j.optLong("aid"), j.optString("bvid"), j.optString("seasonTitle"), j.optString("episodeTitle"),
            j.optString("cover"), category, areas)
        return PgcProgress(ref, j.optLong("position"), j.optLong("duration"), j.optLong("updated"), j.optBoolean("completed"))
    }

    private class Database(context: Context) : SQLiteOpenHelper(context, "watch-progress.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE progress (account TEXT NOT NULL,bvid TEXT NOT NULL,cid INTEGER NOT NULL,updated INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(account,bvid,cid))")
            createPgcTable(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createPgcTable(db)
        }
        private fun createPgcTable(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS pgc_progress (account TEXT NOT NULL,episode_id INTEGER NOT NULL,updated INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(account,episode_id))")
        }
    }
}
