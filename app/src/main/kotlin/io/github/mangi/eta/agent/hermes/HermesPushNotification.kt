package io.github.mangi.eta.agent.hermes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity

/**
 * Hermes 主动推送的落地方式。
 *
 * cron 与 `send_message` 的回复不属于任何一轮本端发起的对话，正文已经落在服务端会话里；
 * 手机上只在通知里给出摘要，点开由主界面按会话 id 拉取完整历史。
 */
internal object HermesPushNotification {
    private const val CHANNEL = "eta_hermes_push"

    fun show(context: Context, sessionId: String, messageId: String, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hermes 主动消息", NotificationManager.IMPORTANCE_DEFAULT),
        )
        // 同一条消息的后续帧（reply.edit）覆盖同一条通知，不重复提示。
        val notificationId = "$sessionId:$messageId".hashCode()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_CONVERSATION_ID)
            .putExtra(MainActivity.EXTRA_CONVERSATION_ID, sessionId)
        val open = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(160))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1_000)))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(notificationId, notification)
    }
}