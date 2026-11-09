package io.github.mangi.eta.agent.hermes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity

/**
 * 常驻前台服务：维持到 Hermes `eta` platform 的唯一 WebSocket。
 *
 * 这条连接同时承载对话与手机工具（Hermes 把它暴露成本地 MCP server），所以服务必须在
 * Agent 没有运行时也活着，否则手机工具会从 Hermes 侧消失、cron 与主动消息也送不到。
 */
internal class HermesBridgeService : Service() {

    private var connected = false
    private var foregroundActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.hermes_bridge_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ensureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                ensureForeground()
                if (!shouldRun()) {
                    shutdown()
                    return START_NOT_STICKY
                }
                restartLink()
                return START_STICKY
            }
        }
        ensureForeground()
        if (!shouldRun()) {
            shutdown()
            return START_NOT_STICKY
        }
        startLink()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        HermesLinkHolder.setStatusListener(null)
        HermesLinkHolder.close()
        super.onDestroy()
    }

    private fun shouldRun(): Boolean =
        HermesSettings.isEnabled(this) && HermesSettings.isChatConfigured(this)

    private fun startLink() {
        val baseUrl = HermesSettings.baseUrl(this)
        val token = HermesSettings.deviceToken(this)
        if (baseUrl.isBlank() || token.isBlank()) return
        HermesLinkHolder.setStatusListener { isConnected ->
            connected = isConnected
            refreshNotification()
        }
        // 两条连接：platform（工具 + 推送）与 dashboard（完整会话功能）。
        HermesLinkHolder.link(this).connect(baseUrl, token)
        runCatching { HermesRpcHolder.link(this).connect(baseUrl, token) }
            .onFailure { AndroidAgentLogger.warn("Hermes RPC start failed: ${it.message}") }
    }

    /** 设置页保存后按新地址/令牌重连。 */
    private fun restartLink() {
        HermesLinkHolder.close()
        HermesRpcHolder.close()
        connected = false
        startLink()
        refreshNotification()
    }

    private fun ensureForeground() {
        if (foregroundActive) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            foregroundActive = true
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Hermes link foreground failed: type=${failure.safeLogType()}")
        }
    }

    private fun refreshNotification(): Boolean {
        if (!foregroundActive) return false
        return runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            true
        }.getOrDefault(false)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.hermes_bridge_title))
            .setContentText(
                getString(
                    if (connected) R.string.hermes_bridge_connected else R.string.hermes_bridge_connecting,
                ),
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun shutdown() {
        HermesLinkHolder.setStatusListener(null)
        HermesLinkHolder.close()
        HermesRpcHolder.close()
        connected = false
        if (foregroundActive) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "eta_hermes_bridge"
        private const val NOTIFICATION_ID = 1108
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_HERMES_BRIDGE"
        private const val ACTION_RELOAD = "io.github.mangi.eta.action.RELOAD_HERMES_BRIDGE"

        @Volatile
        private var instance: HermesBridgeService? = null

        /** 按当前配置启动，或让已运行的服务用新配置重连。 */
        fun sync(context: Context) {
            val app = context.applicationContext
            val shouldRun = HermesSettings.isEnabled(app) && HermesSettings.isChatConfigured(app)
            if (!shouldRun) {
                runCatching { app.stopService(Intent(app, HermesBridgeService::class.java)) }
                return
            }
            val intent = Intent(app, HermesBridgeService::class.java).setAction(ACTION_RELOAD)
            runCatching { app.startForegroundService(intent) }.onFailure { failure ->
                AndroidAgentLogger.warn("Hermes link start failed: type=${failure.safeLogType()}")
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.startService(Intent(app, HermesBridgeService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
