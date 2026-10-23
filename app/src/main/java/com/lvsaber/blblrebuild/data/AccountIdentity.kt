package com.lvsaber.blblrebuild.data

import java.security.MessageDigest

object AccountIdentity {
    fun fromCookie(cookie: String?): String {
        if (cookie.isNullOrBlank()) return "guest"
        val uid = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("DedeUserID=") }
            ?.substringAfter('=')?.takeIf { it.matches(Regex("[0-9]+")) }
        if (uid != null) return "user:$uid"
        return "session:" + MessageDigest.getInstance("SHA-256").digest(cookie.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
