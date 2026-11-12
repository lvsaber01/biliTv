package com.lvsaber.bilitv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local live bookmarks/recent rooms; signed play URLs and danmaku tokens are never persisted. */
class LiveLibraryRepository private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("live_library", Context.MODE_PRIVATE)

    @Synchronized
    fun favorites(): List<LiveRoomSummary> = read(KEY_FAVORITES)

    @Synchronized
    fun recent(): List<LiveRoomSummary> = read(KEY_RECENT)

    @Synchronized
    fun isFavorite(roomId: Long): Boolean = favorites().any { it.roomId == roomId }

    @Synchronized
    fun toggleFavorite(room: LiveRoomSummary): Boolean {
        val current = favorites().toMutableList()
        val index = current.indexOfFirst { it.roomId == room.roomId }
        val added = index < 0
        if (added) current.add(0, room) else current.removeAt(index)
        write(KEY_FAVORITES, current)
        return added
    }

    @Synchronized
    fun recordWatch(room: LiveRoomSummary) {
        val current = recent().filterNot { it.roomId == room.roomId }.toMutableList()
        current.add(0, room)
        write(KEY_RECENT, current.take(MAX_RECENT))
    }

    private fun read(key: String): List<LiveRoomSummary> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val roomId = item.optLong("room_id")
                if (roomId <= 0L) continue
                add(
                    LiveRoomSummary(
                        roomId = roomId,
                        title = item.optString("title").ifBlank { "直播间 $roomId" },
                        coverUrl = item.optString("cover"),
                        anchorName = item.optString("anchor"),
                        anchorFaceUrl = item.optString("face"),
                        parentAreaId = item.optInt("parent_area_id"),
                        parentAreaName = item.optString("parent_area"),
                        areaId = item.optInt("area_id"),
                        areaName = item.optString("area"),
                        online = item.optLong("online"),
                        liveStatus = item.optInt("live_status", 1),
                        isPortrait = item.optBoolean("portrait"),
                    ),
                )
            }
        }.distinctBy { it.roomId }
    }.getOrDefault(emptyList())

    private fun write(key: String, rooms: List<LiveRoomSummary>) {
        val array = JSONArray()
        rooms.forEach { room ->
            array.put(
                JSONObject()
                    .put("room_id", room.roomId)
                    .put("title", room.title)
                    .put("cover", room.coverUrl)
                    .put("anchor", room.anchorName)
                    .put("face", room.anchorFaceUrl)
                    .put("parent_area_id", room.parentAreaId)
                    .put("parent_area", room.parentAreaName)
                    .put("area_id", room.areaId)
                    .put("area", room.areaName)
                    .put("online", room.online)
                    .put("live_status", room.liveStatus)
                    .put("portrait", room.isPortrait),
            )
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    companion object {
        const val MAX_RECENT = 20
        private const val KEY_FAVORITES = "favorite_rooms"
        private const val KEY_RECENT = "recent_rooms"
        @Volatile private var instance: LiveLibraryRepository? = null

        fun get(context: Context): LiveLibraryRepository = instance ?: synchronized(this) {
            instance ?: LiveLibraryRepository(context.applicationContext).also { instance = it }
        }
    }
}
