package com.bitboard.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bitboard.app.App

/**
 * Foreground service that keeps the BitBoard engine (torrent session + LAN
 * beacon) alive while the app is backgrounded, so seeding and replication
 * continue, the whole point of the app.
 */
class BitBoardService : Service() {

    companion object {
        const val CHANNEL_ID = "bitboard_sync"
        const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, BitBoardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BitBoardService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        App.engine(this).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        App.engine(this).stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Board sync", NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "Keeps BitBoard seeding and syncing in the background"
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("BitBoard syncing")
            .setContentText("Seeding and replicating boards over P2P")
            .setOngoing(true)
            .build()
}
