package com.example.appguard

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

class MainActivity : Activity() {

    private data class AppItem(val pkg: String, val label: String, val icon: Drawable)

    private var apps: List<AppItem> = emptyList()
    private lateinit var statusText: TextView
    private lateinit var statusButton: Button
    private lateinit var adapter: AppAdapter

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(40), dp(16), 0)
        }

        root.addView(TextView(this).apply {
            text = "用量守門員"
            textSize = 26f
            setTextColor(Color.parseColor("#212121"))
        })

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(statusText)

        statusButton = Button(this).apply {
            text = "前往開啟「無障礙」權限"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(statusButton)

        root.addView(TextView(this).apply {
            text = "點選下方軟體來設定限制："
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, dp(12), 0, dp(4))
        })

        val list = ListView(this)
        adapter = AppAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> showSettingDialog(apps[pos]) }
        root.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        adapter.notifyDataSetChanged()
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .map { (pkg, ri) -> AppItem(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
        adapter.notifyDataSetChanged()
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, GuardService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun refreshStatus() {
        if (isServiceEnabled()) {
            statusText.text = "✅ 服務運作中"
            statusText.setTextColor(Color.parseColor("#2E7D32"))
            statusButton.visibility = View.GONE
        } else {
            statusText.text = "⚠️ 尚未開啟無障礙服務，限制不會生效"
            statusText.setTextColor(Color.parseColor("#C62828"))
            statusButton.visibility = View.VISIBLE
        }
    }

    private fun summary(pkg: String): String = when (Prefs.getMode(this, pkg)) {
        Prefs.MODE_TIMER -> "每使用 ${Prefs.getMinutes(this, pkg)} 分鐘詢問是否繼續"
        Prefs.MODE_ASK -> "開啟時詢問是否有必要使用"
        else -> "未設定限制"
    }

    private fun showSettingDialog(item: AppItem) {
        val mode = Prefs.getMode(this, item.pkg)
        val minutes = Prefs.getMinutes(this, item.pkg)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val group = RadioGroup(this)
        val rNone = RadioButton(this).apply { text = "不限制"; id = View.generateViewId() }
        val rTimer = RadioButton(this).apply {
            text = "第一項：開啟後計時，時間到詢問是否繼續"
            id = View.generateViewId()
        }
        val rAsk = RadioButton(this).apply {
            text = "第二項：一開啟就詢問是否有必要使用"
            id = View.generateViewId()
        }
        group.addView(rNone)
        group.addView(rTimer)
        group.addView(rAsk)
        box.addView(group)

        val minutesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(32), 0, 0, 0)
        }
        minutesRow.addView(TextView(this).apply { text = "每次計時（分鐘）：" })
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(minutes.toString())
            minEms = 3
        }
        minutesRow.addView(input)
        box.addView(minutesRow)

        fun updateRow() {
            val on = group.checkedRadioButtonId == rTimer.id
            minutesRow.alpha = if (on) 1f else 0.4f
            input.isEnabled = on
        }
        group.setOnCheckedChangeListener { _, _ -> updateRow() }
        group.check(
            when (mode) {
                Prefs.MODE_TIMER -> rTimer.id
                Prefs.MODE_ASK -> rAsk.id
                else -> rNone.id
            }
        )
        updateRow()

        AlertDialog.Builder(this)
            .setTitle(item.label)
            .setView(box)
            .setPositiveButton("儲存") { _, _ ->
                val newMode = when (group.checkedRadioButtonId) {
                    rTimer.id -> Prefs.MODE_TIMER
                    rAsk.id -> Prefs.MODE_ASK
                    else -> Prefs.MODE_NONE
                }
                val m = input.text.toString().toIntOrNull()?.coerceIn(1, 1440)
                    ?: Prefs.defaultMinutes()
                Prefs.set(this, item.pkg, newMode, m)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = apps[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(ImageView(context).apply { tag = "icon" }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(TextView(context).apply {
                        tag = "name"; textSize = 16f; setTextColor(Color.parseColor("#212121"))
                    })
                    addView(TextView(context).apply {
                        tag = "sub"; textSize = 13f
                    })
                })
            }
            row.findViewWithTag<ImageView>("icon").setImageDrawable(item.icon)
            row.findViewWithTag<TextView>("name").text = item.label
            row.findViewWithTag<TextView>("sub").apply {
                text = summary(item.pkg)
                setTextColor(
                    if (Prefs.getMode(this@MainActivity, item.pkg) == Prefs.MODE_NONE)
                        Color.GRAY else Color.parseColor("#1565C0")
                )
            }
            return row
        }
    }
}
