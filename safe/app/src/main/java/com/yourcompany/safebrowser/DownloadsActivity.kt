package com.yourcompany.safebrowser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Issue 2: there was previously no way to see past downloads inside the app -- they only
 * showed up as a one-off Toast and a system notification. This lists everything recorded by
 * DownloadStore, lets the user open a file with whatever app handles it, share it, or delete it
 * (which also removes the file from disk and from DownloadManager's own table).
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var store: DownloadStore
    private lateinit var listLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DownloadStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val titleBar = TextView(this).apply {
            text = "Downloads"
            textSize = 20f
            setTextColor(Color.parseColor("#202124"))
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        root.addView(titleBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listLayout)
        root.addView(scroll)

        setContentView(root)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun refreshList() {
        listLayout.removeAllViews()
        val records = store.getAll()
        if (records.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "No downloads yet"
                setTextColor(Color.parseColor("#5F6368"))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(16))
            })
            return
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dateFmt = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

        for (record in records) {
            val (status, sizeBytes) = queryDownloadManager(dm, record.downloadId)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                setOnClickListener { openDownload(record) }
            }
            row.addView(TextView(this).apply {
                text = record.fileName
                textSize = 15f
                setTextColor(Color.parseColor("#202124"))
            })
            val sizeText = if (sizeBytes > 0) Formatter.formatShortFileSize(this, sizeBytes) else ""
            row.addView(TextView(this).apply {
                text = listOfNotNull(
                    dateFmt.format(Date(record.timestamp)),
                    record.folder.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                    sizeText.ifBlank { null },
                    status
                ).joinToString(" • ")
                textSize = 12f
                setTextColor(Color.parseColor("#5F6368"))
            })

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            actions.addView(Button(this).apply {
                text = "Share"
                setOnClickListener { shareDownload(record) }
            })
            actions.addView(Button(this).apply {
                text = "Delete"
                setOnClickListener { deleteDownload(record) }
            })
            row.addView(actions)

            listLayout.addView(row)
            listLayout.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#E0E0E0"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
    }

    private fun queryDownloadManager(dm: DownloadManager, downloadId: Long): Pair<String, Long> {
        var status = ""
        var size = 0L
        var cursor: Cursor? = null
        try {
            cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
            if (cursor != null && cursor.moveToFirst()) {
                val statusCol = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                size = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                status = when (statusCol) {
                    DownloadManager.STATUS_SUCCESSFUL -> "Completed"
                    DownloadManager.STATUS_FAILED -> "Failed"
                    DownloadManager.STATUS_RUNNING -> "Downloading"
                    DownloadManager.STATUS_PENDING -> "Pending"
                    DownloadManager.STATUS_PAUSED -> "Paused"
                    else -> ""
                }
            }
        } catch (e: Exception) {
            // record may have been deleted from DownloadManager's own table; ignore
        } finally {
            cursor?.close()
        }
        return status to size
    }

    private fun fileFor(record: DownloadRecord): File =
        File(Environment.getExternalStoragePublicDirectory(record.folder), record.fileName)

    private fun openDownload(record: DownloadRecord) {
        val file = fileFor(record)
        if (!file.exists()) {
            Toast.makeText(this, "File not found on disk", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, record.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDownload(record: DownloadRecord) {
        val file = fileFor(record)
        if (!file.exists()) {
            Toast.makeText(this, "File not found on disk", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = record.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share file"))
    }

    private fun deleteDownload(record: DownloadRecord) {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        try { dm.remove(record.downloadId) } catch (e: Exception) {}
        try { fileFor(record).delete() } catch (e: Exception) {}
        store.remove(record.downloadId)
        refreshList()
    }
}
