package com.example.ladastyleplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.ladastyleplayer.MainActivity
import com.example.ladastyleplayer.R

class PlayerNotificationHelper(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(isPlaying: Boolean, title: String): Notification {
        val prevIntent = actionIntent(PlayerService.ACTION_PREV, 1)
        val playPauseIntent = actionIntent(PlayerService.ACTION_PLAY_PAUSE, 2)
        val nextIntent = actionIntent(PlayerService.ACTION_NEXT, 3)
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(title)
            .setContentIntent(openAppIntent)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, context.getString(R.string.prev), prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                context.getString(R.string.play_pause),
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, context.getString(R.string.next), nextIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PlayerService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "lada_style_player_channel"
    }
}
