package com.lvsaber.bilitv.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Current WBI signing implementation used by Bilibili web endpoints. */
object BiliWbiSigner {
    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    fun sign(input: Map<String, String>, imgKey: String, subKey: String, timestampSeconds: Long): Map<String, String> {
        val mixinKey = mixinKey(imgKey + subKey)
        val params = input.toMutableMap()
        params["wts"] = timestampSeconds.toString()
        val query = params.toSortedMap().entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(filter(value))}"
        }
        params["w_rid"] = md5(query + mixinKey)
        return params
    }

    private fun mixinKey(value: String): String = buildString {
        mixinKeyEncTab.forEach { index ->
            if (index < value.length) append(value[index])
        }
    }.take(32)

    private fun filter(value: String): String = value.replace(Regex("[!'()*]"), "")

    private fun encode(value: String): String = URLEncoder
        .encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
        .replace("%7E", "~")

    private fun md5(value: String): String = MessageDigest
        .getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

