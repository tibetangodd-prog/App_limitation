package com.example.appguard

import android.content.Context

/** 每個軟體的限制設定，存在 SharedPreferences。 */
object Prefs {
    const val MODE_NONE = 0   // 不限制
    const val MODE_TIMER = 1  // 開啟後 N 分鐘詢問是否繼續
    const val MODE_ASK = 2    // 一開啟就詢問是否有必要使用

    private const val FILE = "guard_prefs"
    private const val DEFAULT_MINUTES = 15

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getMode(c: Context, pkg: String): Int = sp(c).getInt("mode_$pkg", MODE_NONE)

    fun getMinutes(c: Context, pkg: String): Int = sp(c).getInt("min_$pkg", DEFAULT_MINUTES)

    fun set(c: Context, pkg: String, mode: Int, minutes: Int) {
        sp(c).edit().putInt("mode_$pkg", mode).putInt("min_$pkg", minutes).apply()
    }

    fun defaultMinutes() = DEFAULT_MINUTES
}
