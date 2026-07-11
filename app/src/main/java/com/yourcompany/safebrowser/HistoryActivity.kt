package com.yourcompany.safebrowser

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var listLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val titleBar = TextView(this).apply {
            text = "Session History"
            textSize = 20f
            setTextColor(Color.parseColor("#202124"))
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        root.addView(titleBar)

        val clearBtn = TextView(this).apply {
            text = "CLEAR HISTORY"
            textSize = 14f
            setTextColor(Color.parseColor("#1A73E8"))
            setPadding(dp(16), dp(8), dp(16), dp(16))
            isClickable = true
            setOnClickListener {
                SessionHistoryManager.clear()
                refreshList()
            }
        }
        root.addView(clearBtn)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listLayout)
        root.addView(scroll)

        setContentView(root)
        refreshList()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun refreshList() {
        listLayout.removeAllViews()
        val records = SessionHistoryManager.getAll()
        if (records.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "No history yet"
                setTextColor(Color.parseColor("#5F6368"))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(16))
            })
            return
        }

        val dateFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

        for (record in records) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                setOnClickListener {
                    val intent = Intent(this@HistoryActivity, MainActivity::class.java).apply {
                        putExtra("loadUrl", record.url)
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    finish()
                }
            }
            row.addView(TextView(this).apply {
                text = record.title.ifEmpty { record.url }
                textSize = 15f
                setTextColor(Color.parseColor("#202124"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(TextView(this).apply {
                text = "${dateFmt.format(Date(record.timestamp))} • ${record.url}"
                textSize = 12f
                setTextColor(Color.parseColor("#5F6368"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            listLayout.addView(row)
            listLayout.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#E0E0E0"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
    }
}
