package com.example.xiquercatch.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.xiquercatch.R
import kotlinx.coroutines.launch

class LogActivity : AppCompatActivity() {
    private lateinit var tvLogs: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        tvLogs = findViewById(R.id.tvLogs)
        findViewById<Button>(R.id.btnLogClear).setOnClickListener {
            AppLog.clear()
        }
        findViewById<Button>(R.id.btnLogCopy).setOnClickListener {
            copyLogs()
        }
        findViewById<Button>(R.id.btnLogClose).setOnClickListener {
            finish()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppLog.entries.collect { lines ->
                    tvLogs.text = lines.joinToString("\n")
                }
            }
        }
    }

    private fun copyLogs() {
        val text = tvLogs.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("xiquercatch_logs", text))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }
}
