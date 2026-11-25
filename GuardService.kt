package com.example.appguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 無障礙服務：偵測前景軟體，並依設定顯示詢問視窗。
 *
 * 一個「使用階段」＝從被監控軟體進到前景，到切換到其他軟體 / 回桌面 / 螢幕關閉為止。
 * 階段結束時，計時與視窗一律重置，下次再開啟就重新開始。
 */
class GuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var currentPkg: String? = null   // 目前正在被監控的軟體（無則為 null）
    private var overlay: FrameLayout? = null
    private var timerTask: Runnable? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = endSession()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // 忽略自己（詢問視窗）、系統介面與輸入法，避免誤判成「離開軟體」
        if (pkg == packageName || pkg == "com.android.systemui" || isInputMethod(pkg)) return

        if (pkg == currentPkg) return  // 仍在同一個軟體內，不重複處理

        // 切換到不同的軟體：結束上一個階段
        endSession()

        val mode = Prefs.getMode(this, pkg)
        if (mode == Prefs.MODE_NONE) return

        currentPkg = pkg
        when (mode) {
            Prefs.MODE_TIMER -> scheduleTimer(pkg)
            Prefs.MODE_ASK -> showDialog(
                pkg,
                message = "你有必要使用「${labelOf(pkg)}」嗎？",
                continueText = "有必要，繼續使用",
                closeText = "沒必要，關閉"
            ) { /* 選擇繼續後不再詢問，直到此階段結束 */ }
        }
    }

    // ---------- 第一項：計時詢問 ----------

    private fun scheduleTimer(pkg: String) {
        val minutes = Prefs.getMinutes(this, pkg).coerceAtLeast(1)
        val task = Runnable {
            if (currentPkg != pkg) return@Runnable
            showDialog(
                pkg,
                message = "你已經使用「${labelOf(pkg)}」$minutes 分鐘了，要繼續使用嗎？",
                continueText = "繼續使用",
                closeText = "關閉軟體"
            ) {
                // 選擇繼續 → 再計時一輪
                if (currentPkg == pkg) scheduleTimer(pkg)
            }
        }
        timerTask = task
        handler.postDelayed(task, minutes * 60_000L)
    }

    // ---------- 詢問視窗 ----------

    private fun showDialog(
        pkg: String,
        message: String,
        continueText: String,
        closeText: String,
        onContinue: () -> Unit
    ) {
        removeOverlay()

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
        }
        card.addView(TextView(this).apply {
            text = message
            textSize = 18f
            setTextColor(Color.parseColor("#212121"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(20))
        })
        card.addView(Button(this).apply {
            text = continueText
            setOnClickListener {
                removeOverlay()
                onContinue()
            }
        })
        card.addView(Button(this).apply {
            text = closeText
            setOnClickListener {
                endSession()
                performGlobalAction(GLOBAL_ACTION_HOME)  // 回到桌面 = 關閉該軟體
            }
        })

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#B3000000"))
            isClickable = true  // 吃掉點擊，避免點到底下的軟體
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ).apply { setMargins(dp(32), 0, dp(32), 0) }
            )
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(root, lp)
            overlay = root
        } catch (_: Exception) {
        }
    }

    private fun removeOverlay() {
        overlay?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
    }

    // ---------- 工具 ----------

    private fun endSession() {
        timerTask?.let { handler.removeCallbacks(it) }
        timerTask = null
        removeOverlay()
        currentPkg = null
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun isInputMethod(pkg: String): Boolean =
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .enabledInputMethodList.any { it.packageName == pkg }
        } catch (_: Exception) {
            false
        }

    override fun onInterrupt() = endSession()

    override fun onDestroy() {
        endSession()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
