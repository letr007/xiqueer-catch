package com.example.xiquercatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.xiquercatch.capture.CaptureStateBus
import com.example.xiquercatch.capture.PacketCaptureService
import com.example.xiquercatch.data.ServiceLocator
import com.example.xiquercatch.data.model.SemesterGroup
import com.example.xiquercatch.debug.AppLog
import com.example.xiquercatch.debug.LogActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSummary: TextView
    private lateinit var container: LinearLayout

    private val selectedSnapshotIds = linkedSetOf<Long>()
    private var latestGroups: List<SemesterGroup> = emptyList()
    private var lastLoggedCaptureError: String = ""

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) {
            AppLog.i(TAG, "VPN permission granted")
            startCaptureService()
        } else {
            AppLog.w(TAG, "VPN permission denied")
            toast("VPN 授权被拒绝")
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            exportSelected()
        } else {
            toast("未授予存储权限，无法导出到下载目录")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(this)
        AppLog.i(TAG, "MainActivity created")
        setContentView(R.layout.activity_main)
        bindViews()
        bindActions()
        observeState()
        renderGroups(ServiceLocator.repository.getGroupedSnapshots())
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvSummary = findViewById(R.id.tvSummary)
        container = findViewById(R.id.layoutSnapshotContainer)
    }

    private fun bindActions() {
        findViewById<Button>(R.id.btnStartCapture).setOnClickListener {
            AppLog.i(TAG, "Click start capture")
            ensureNotificationPermissionIfNeeded()
            val intent = VpnService.prepare(this)
            if (intent != null) {
                AppLog.i(TAG, "Requesting VPN permission")
                vpnPermissionLauncher.launch(intent)
            } else {
                startCaptureService()
            }
        }
        findViewById<Button>(R.id.btnStopCapture).setOnClickListener {
            AppLog.i(TAG, "Click stop capture")
            stopCaptureService()
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            AppLog.i(TAG, "Click refresh data")
            renderGroups(ServiceLocator.repository.getGroupedSnapshots())
        }
        findViewById<Button>(R.id.btnClearData).setOnClickListener {
            ServiceLocator.repository.deleteAll()
            selectedSnapshotIds.clear()
            renderGroups(emptyList())
            AppLog.i(TAG, "Snapshots cleared")
            toast("已清空")
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            AppLog.i(TAG, "Click export")
            exportSelected()
        }
        findViewById<Button>(R.id.btnOpenLogs).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<Button>(R.id.btnHowToUse).setOnClickListener {
            showUsageGuide()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CaptureStateBus.state.collect { state ->
                        tvStatus.text = buildString {
                            append("状态：")
                            append(if (state.running) "抓取中" else "未运行")
                            append("  已捕获：${state.capturedCount}")
                            if (state.lastError.isNotBlank()) {
                                append("\n错误：${state.lastError}")
                            }
                        }
                        if (state.lastError.isNotBlank()) {
                            if (state.lastError != lastLoggedCaptureError) {
                                AppLog.w(TAG, "Capture state error: ${state.lastError}")
                                lastLoggedCaptureError = state.lastError
                            }
                        } else {
                            lastLoggedCaptureError = ""
                        }
                    }
                }
                launch {
                    ServiceLocator.repository.snapshots.collect {
                        renderGroups(ServiceLocator.repository.getGroupedSnapshots())
                    }
                }
            }
        }
    }

    private fun renderGroups(groups: List<SemesterGroup>) {
        latestGroups = groups
        container.removeAllViews()
        if (groups.isEmpty()) {
            tvSummary.text = getString(R.string.no_snapshot)
            return
        }
        groups.forEach { group ->
            val header = TextView(this).apply {
                textSize = 16f
                text = "${group.title}（${group.snapshots.size}条）"
                setPadding(0, 16, 0, 8)
            }
            container.addView(header)
            group.snapshots.forEach { snapshot ->
                val checkBox = CheckBox(this).apply {
                    val checked = selectedSnapshotIds.contains(snapshot.id)
                    isChecked = checked
                    text = "第${snapshot.zc}周 ${snapshot.qssj}~${snapshot.jssj} 课程数:${snapshot.courses.size}"
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selectedSnapshotIds.add(snapshot.id)
                        else selectedSnapshotIds.remove(snapshot.id)
                        refreshSummary()
                    }
                }
                container.addView(checkBox)
            }
        }
        refreshSummary()
    }

    private fun refreshSummary() {
        if (latestGroups.isEmpty()) {
            tvSummary.text = getString(R.string.no_snapshot)
            return
        }
        val selectedSnapshots = ServiceLocator.repository.getByIds(selectedSnapshotIds)
        val grouped = selectedSnapshots.groupBy { it.xn to it.xq }
        val coverageText = grouped.entries.joinToString("；") { (entry, snapshots) ->
            val (xn, xq) = entry
            val maxWeek = snapshots.firstOrNull()?.maxzc ?: 0
            val covered = snapshots.map { it.zc }.toSet().size
            "$xn 学年 第${displaySemester(xq)}学期 覆盖 $covered/$maxWeek 周"
        }
        tvSummary.text = "分组数:${latestGroups.size}  已选择:${selectedSnapshotIds.size}" +
            if (coverageText.isNotBlank()) "\n$coverageText" else ""
    }

    private fun displaySemester(rawXq: String): String {
        val value = rawXq.toIntOrNull() ?: return rawXq
        return (value + 1).toString()
    }

    private fun showUsageGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.usage_guide_title)
            .setMessage(getString(R.string.usage_guide_content))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun startCaptureService() {
        val intent = Intent(this, PacketCaptureService::class.java).apply {
            action = PacketCaptureService.ACTION_START
        }
        AppLog.i(TAG, "Starting PacketCaptureService")
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopCaptureService() {
        val intent = Intent(this, PacketCaptureService::class.java).apply {
            action = PacketCaptureService.ACTION_STOP
        }
        AppLog.i(TAG, "Stopping PacketCaptureService")
        startService(intent)
    }

    private fun exportSelected() {
        if (selectedSnapshotIds.isEmpty()) {
            AppLog.w(TAG, "Export blocked: no snapshots selected")
            toast("请先勾选要导出的周快照")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val snapshots = ServiceLocator.repository.getByIds(selectedSnapshotIds)
            val rows = ServiceLocator.mergeService.mergeToRows(snapshots)
            val result = ServiceLocator.csvExporter.export(this@MainActivity, rows)
            withContext(Dispatchers.Main) {
                if (result.success) {
                    AppLog.i(TAG, "Export success: ${result.path}, rows=${rows.size}")
                    toast("导出成功：${result.path}")
                } else {
                    AppLog.e(TAG, "Export failed: ${result.message.orEmpty()}")
                    toast(result.message ?: "导出失败")
                }
            }
        }
    }

    private fun ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_NOTIFY = 101
    }
}
