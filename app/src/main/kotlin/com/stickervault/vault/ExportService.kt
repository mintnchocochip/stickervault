package com.stickervault.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.stickervault.MainActivity
import com.stickervault.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the archive as a foreground service so it survives the user leaving the
 * app. Archiving a real library takes minutes; without this, Android is free to
 * freeze or kill the work the moment the screen turns off, and the user comes
 * back to a half-finished job with no idea it stopped.
 */
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastNotifiedPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        startForeground(
            PROGRESS_NOTIFICATION_ID,
            progressNotification(0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        val files = VaultRepository.files
        if (files.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch {
            VaultRepository.status.value = VaultRepository.Status.Archiving(0, files.size)
            try {
                val result = VaultExporter(applicationContext).export(files) { p ->
                    VaultRepository.status.value =
                        VaultRepository.Status.Archiving(p.done, p.total)
                    maybeUpdateProgressNotification(p.done, p.total)
                }

                VaultRepository.status.value = VaultRepository.Status.Finished(
                    summary = result.summary,
                    uri = result.uri,
                    displayName = result.displayName,
                )
                notifyFinished(result.summary.unique, result.displayName)
            } catch (t: Throwable) {
                val message = t.message ?: "Export failed"
                VaultRepository.status.value = VaultRepository.Status.Failed(message)
                notifyFailed(message)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ notifications

    private fun manager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            "Archiving",
            // Low: this updates constantly and must never buzz the phone.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }

        val done = NotificationChannel(
            CHANNEL_DONE,
            "Archive complete",
            NotificationManager.IMPORTANCE_DEFAULT,
        )

        manager().createNotificationChannels(listOf(progress, done))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(done: Int, total: Int): Notification {
        val text = if (total == 0) "Preparing…" else "$done of $total stickers"
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle("Archiving stickers")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vault)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setContentIntent(openAppIntent())
            .build()
    }

    /**
     * Posting on every batch would mean hundreds of updates across a large
     * library. One per whole percent is plenty and keeps the system happy.
     */
    private fun maybeUpdateProgressNotification(done: Int, total: Int) {
        if (total <= 0) return
        val percent = done * 100 / total
        if (percent == lastNotifiedPercent) return
        lastNotifiedPercent = percent
        runCatching {
            manager().notify(PROGRESS_NOTIFICATION_ID, progressNotification(done, total))
        }
    }

    private fun notifyFinished(unique: Int, displayName: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setContentTitle("Sticker vault saved")
            .setContentText("$unique stickers archived to $displayName")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$unique unique stickers archived to Downloads/$displayName. " +
                        "Tap to open and send it to Drive.",
                ),
            )
            .setSmallIcon(R.drawable.ic_stat_vault)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { manager().notify(DONE_NOTIFICATION_ID, notification) }
    }

    private fun notifyFailed(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setContentTitle("Archiving failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_stat_vault)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { manager().notify(DONE_NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val CHANNEL_PROGRESS = "export_progress"
        private const val CHANNEL_DONE = "export_done"
        private const val PROGRESS_NOTIFICATION_ID = 1001
        private const val DONE_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ExportService::class.java))
        }
    }
}
