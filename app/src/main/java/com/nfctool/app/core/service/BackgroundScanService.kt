package com.nfctool.app.core.service

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.nfc.NfcAdapter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nfctool.app.MainActivity

/**
 * 后台读卡前台服务。
 *
 * ## 为什么需要它
 * Android 10 (API 29) 起，系统限制后台应用自动接收标签 Intent：
 * 应用不在前台时，标签不会自动唤起应用。想在后台读卡，
 * 必须用前台服务（类型 connectedDevice）保持进程存活与 NFC 活动能力。
 *
 * ## 为什么不用 enableReaderMode
 * [NfcAdapter.enableReaderMode] 的第一个参数是 **Activity**，
 * Service 无法作为宿主，因此后台场景不能直接用 reader mode。
 *
 * ## 实际方案
 * 1. 本服务以 connectedDevice 类型启动为前台服务
 * 2. 服务内部动态注册标签广播接收器，监听标签发现事件
 *    （动态注册比静态注册更可靠：静态注册的 receiver 在后台被唤起时，
 *    Android 不允许它启动 Activity）
 * 3. 收到标签后通过 [BackgroundScanBus] 通知 UI
 *
 * 前提：用户需在「设置 → 默认应用 → NFC」中允许本应用接收标签事件
 * （对应 [NfcAdapter.isTagIntentAllowed]，API 36+）。
 */
class BackgroundScanService : Service() {

    private var tagReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        registerTagReceiver()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterTagReceiver()
        super.onDestroy()
    }

    /**
     * 动态注册标签接收器。
     *
     * Android 14 (API 34) 起，动态注册广播接收器必须显式声明导出行为，
     * 否则会抛 SecurityException。
     */
    private fun registerTagReceiver() {
        if (tagReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val tag = extractTag(intent) ?: return
                BackgroundScanBus.emit(context, tag, extractId(intent))
            }
        }

        val filter = IntentFilter().apply {
            addAction(NfcAdapter.ACTION_TAG_DISCOVERED)
            addAction(NfcAdapter.ACTION_NDEF_DISCOVERED)
            addAction(NfcAdapter.ACTION_TECH_DISCOVERED)
            addAction(ACTION_TAG_FOUND)
        }

        try {
            // ContextCompat.registerReceiver 返回最后一次注册的 Intent，
            // receiver 需自行持有以便反注册。
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
            tagReceiver = receiver
        } catch (e: Exception) {
            tagReceiver = null
        }
    }

    private fun unregisterTagReceiver() {
        tagReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        tagReceiver = null
    }

    private fun extractTag(intent: Intent): android.nfc.Tag? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, android.nfc.Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) as? android.nfc.Tag
        }

    private fun extractId(intent: Intent): ByteArray? =
        intent.getByteArrayExtra(NfcAdapter.EXTRA_ID)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            AppStrings.get(R.string.bg_scan_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = AppStrings.get(R.string.bg_scan_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(AppStrings.get(R.string.bg_scan_title))
            .setContentText(AppStrings.get(R.string.bg_scan_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "nfctool_background_scan"
        private const val NOTIFICATION_ID = 1001

        /** 广播 action：发现标签 */
        const val ACTION_TAG_FOUND = "com.nfctool.app.action.TAG_FOUND"

        /** 启动后台扫描服务 */
        fun start(context: Context) {
            val intent = Intent(context, BackgroundScanService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        /** 停止后台扫描服务 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, BackgroundScanService::class.java))
            }
        }
    }
}
