package com.example.xiquercatch.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.xiquercatch.MainActivity
import com.example.xiquercatch.R
import com.example.xiquercatch.data.ServiceLocator
import com.example.xiquercatch.data.model.ScheduleSnapshot
import com.example.xiquercatch.debug.AppLog
import com.example.xiquercatch.parser.ScheduleResponseParser
import java.util.concurrent.atomic.AtomicBoolean

class PacketCaptureService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyServer: ScheduleHttpProxyServer? = null
    private val running = AtomicBoolean(false)
    private val responseParser = ScheduleResponseParser()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> startCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture() {
        if (!running.compareAndSet(false, true)) return
        AppLog.i(TAG, "startCapture invoked")
        ServiceLocator.init(this)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            CaptureStateBus.update {
                it.copy(
                    running = false,
                    lastError = "Android 10 以下系统不支持自动代理模式"
                )
            }
            AppLog.w(TAG, "Unsupported sdk for auto proxy: ${Build.VERSION.SDK_INT}")
            stopSelf()
            return
        }

        try {
            val notification = buildNotification("正在代理并抓取课表请求")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            CaptureStateBus.update {
                it.copy(running = false, lastError = "前台服务启动失败: ${t.message.orEmpty()}")
            }
            AppLog.e(TAG, "Foreground start failed", t)
            running.set(false)
            stopSelf()
            return
        }
        CaptureStateBus.update { it.copy(running = true, lastError = "") }
        AppLog.i(TAG, "Foreground service started")

        val proxy = ScheduleHttpProxyServer(
            protectSocket = { socket -> protect(socket) },
            forceIpv4Hosts = FORCE_IPV4_HOSTS,
            onCaptured = { host, path, reqBody, respBody ->
                runCatching { onCapturedExchange(host, path, reqBody, respBody) }
                    .onFailure { AppLog.e(TAG, "onCapturedExchange failed", it) }
            }
        )
        val proxyPort = runCatching { proxy.start(0) }.getOrElse { error ->
            CaptureStateBus.update {
                it.copy(running = false, lastError = "代理启动失败: ${error.message.orEmpty()}")
            }
            AppLog.e(TAG, "Proxy start failed", error)
            stopSelf()
            return
        }
        proxyServer = proxy
        AppLog.i(TAG, "Proxy started at 127.0.0.1:$proxyPort")
        AppLog.i(TAG, "Force IPv4 hosts: ${FORCE_IPV4_HOSTS.joinToString(",")}")

        try {
            val vpnBuilder = Builder()
                .setSession("XiqueerCatch")
                .addAddress("10.10.10.1", 32)
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", proxyPort))
            AppLog.i(TAG, "Using system DNS + host-based IPv4 force")

            val xiqueerPackages = detectInstalledTargetPackages()
            if (xiqueerPackages.isNotEmpty()) {
                xiqueerPackages.forEach { pkg ->
                    runCatching { vpnBuilder.addAllowedApplication(pkg) }
                        .onFailure { AppLog.w(TAG, "addAllowedApplication failed: $pkg", it) }
                }
                AppLog.i(TAG, "Allowed apps: ${xiqueerPackages.joinToString(",")}")
            } else {
                // Fallback: avoid capturing this app itself to prevent proxy loop.
                runCatching { vpnBuilder.addDisallowedApplication(packageName) }
                    .onFailure { AppLog.w(TAG, "addDisallowedApplication failed", it) }
                AppLog.w(TAG, "No target app found, fallback to disallow self")
            }

            vpnInterface = vpnBuilder.establish()
            AppLog.i(TAG, "VPN established")
        } catch (t: Throwable) {
            CaptureStateBus.update {
                it.copy(running = false, lastError = "建立VPN失败: ${t.message.orEmpty()}")
            }
            AppLog.e(TAG, "VPN establish failed", t)
            stopCapture()
            stopSelf()
        }
    }

    private fun stopCapture() {
        if (!running.compareAndSet(true, false)) return
        AppLog.i(TAG, "stopCapture invoked")
        runCatching { proxyServer?.stop() }
        proxyServer = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        CaptureStateBus.update { it.copy(running = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        AppLog.i(TAG, "Capture stopped")
    }

    private fun onCapturedExchange(host: String, path: String, requestBody: String, responseBody: String) {
        val targetHostMatched = host == TARGET_HOST
        val targetPathMatched = path.contains(TARGET_PATH_PART)
        if (!targetHostMatched || !targetPathMatched) {
            AppLog.i(
                TAG,
                "Captured HTTP exchange not target host/path: host=$host path=$path"
            )
            return
        }
        if (!responseBody.contains("{jcflag:'")) {
            AppLog.w(
                TAG,
                "Target host/path matched but response has no jcflag, prefix=${responseBody.take(800)}"
            )
            return
        }

        val parsed = responseParser.parseResponseBody(responseBody)
        if (parsed == null) {
            AppLog.w(TAG, "Response matched path but parse failed, bodyPrefix=${responseBody.take(80)}")
            return
        }
        val snapshot = ScheduleSnapshot(
            id = System.currentTimeMillis() + (0..999).random(),
            capturedAt = System.currentTimeMillis(),
            host = host,
            path = path,
            xn = parsed.xn,
            xq = parsed.xq,
            zc = parsed.zc,
            maxzc = parsed.maxzc,
            qssj = parsed.qssj,
            jssj = parsed.jssj,
            requestParamDigest = responseParser.parseRequestParamDigest(requestBody),
            courses = parsed.courses
        )
        ServiceLocator.repository.saveSnapshot(snapshot)
        AppLog.i(
            TAG,
            "Captured schedule snapshot xn=${parsed.xn} xq=${parsed.xq} zc=${parsed.zc} courses=${parsed.courses.size}"
        )
        CaptureStateBus.update {
            it.copy(
                capturedCount = it.capturedCount + 1,
                lastCaptureTime = System.currentTimeMillis(),
                lastError = ""
            )
        }
    }

    private fun detectInstalledTargetPackages(): List<String> {
        val candidates = listOf(
            "com.kingosoft.activity_kb_common",
            "com.wisedu.xiqueer",
            "com.newcapec.mobile.ncp",
            "com.supwisdom.xiqueer"
        )
        val installed = mutableListOf<String>()
        candidates.forEach { pkg ->
            val exists = runCatching {
                packageManager.getPackageInfo(pkg, 0)
                true
            }.onFailure { e ->
                AppLog.w(TAG, "Package probe failed: $pkg", e)
            }.getOrDefault(false)
            if (exists) {
                installed += pkg
            }
        }
        return installed
    }

    private fun buildNotification(content: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "课表抓取服务",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("喜鹊儿课表抓取中")
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PacketCaptureService"
        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TARGET_HOST = "jw.xxgc.edu.cn"
        private const val TARGET_PATH_PART = "/jwweb//wap/mycourseschedule.aspx"
        private val FORCE_IPV4_HOSTS = setOf(TARGET_HOST, "api.xiqueer.com")

        const val ACTION_START = "com.example.xiquercatch.capture.START"
        const val ACTION_STOP = "com.example.xiquercatch.capture.STOP"
    }
}
