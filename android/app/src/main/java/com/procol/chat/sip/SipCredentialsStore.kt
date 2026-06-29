package com.procol.chat.sip

import android.content.Context
import android.content.SharedPreferences

object SipCredentialsStore {

    private const val PREFS_NAME = "sip_credentials"
    private const val KEY_CREDENTIALS = "credentials_json"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, credentialsJson: String) {
        prefs(context).edit().putString(KEY_CREDENTIALS, credentialsJson).apply()
    }

    fun read(context: Context): String? =
        prefs(context).getString(KEY_CREDENTIALS, null)

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CREDENTIALS).apply()
    }
}
