package com.transferhub.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 简单的本地设置。 */
object Settings {
    private const val PREFS = "settings"
    private const val KEY_WEB_DELETE = "allow_web_delete"

    private val _allowWebDelete = MutableStateFlow(false)
    val allowWebDelete: StateFlow<Boolean> = _allowWebDelete.asStateFlow()

    fun init(context: Context) {
        _allowWebDelete.value = prefs(context).getBoolean(KEY_WEB_DELETE, false)
    }

    fun setAllowWebDelete(context: Context, value: Boolean) {
        _allowWebDelete.value = value
        prefs(context).edit().putBoolean(KEY_WEB_DELETE, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
