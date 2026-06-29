package com.procol.chat.sip

import android.content.Context

object FcmTokenStore {

    private const val PREFS_NAME = "fcm_token"
    private const val KEY_TOKEN = "token"

    fun save(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
    }

    fun read(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
}
