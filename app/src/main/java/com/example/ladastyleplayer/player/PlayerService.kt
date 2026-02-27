package com.example.ladastyleplayer.player

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.ladastyleplayer.R

/**
 * Foreground playback service for playing USB media items and persisting playback state.
 */
class PlayerService : Service() {

    private lateinit var player: ExoPlayer
    private lateinit var notificationHelper: PlayerNotificationHelper
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var currentTitle: String = ""
    private var muted = false

    override fun onCreate() {
        super.onCreate()
        notificationHelper = PlayerNotificationHelper(this)
        notificationHelper.ensureChannel()
        player = ExoPlayer.Builder(this).build()
        muted = prefs.getBoolean(KEY_MUTED, false)
        player.volume = if (muted) 0f else 1f
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification(isPlaying)
                persistPlaybackState()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTitle = mediaItem?.mediaMetadata?.title?.toString()
                    ?: mediaItem?.localConfiguration?.uri?.lastPathSegment
                    ?: getString(R.string.no_track)
                sendTrackChanged(mediaItem?.localConfiguration?.uri)
                updateNotification(player.isPlaying)
                persistPlaybackState()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "Playback error, skipping", error)
                player.seekToNextMediaItem()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_FOLDER -> handlePlayFolder(intent)
            ACTION_PLAY_SINGLE -> handlePlaySingle(intent)
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> player.seekToNextMediaItem()
            ACTION_PREV -> player.seekToPreviousMediaItem()
            ACTION_TOGGLE_MUTE -> toggleMute()
        }
        return START_STICKY
    }

    private fun handlePlayFolder(intent: Intent) {
        val uris = intent.getStringArrayListExtra(EXTRA_URI_LIST)?.map(Uri::parse).orEmpty()
        if (uris.isEmpty()) return
        val items = uris.map { MediaItem.fromUri(it) }
        player.setMediaItems(items)
        player.prepare()

        if (intent.getBooleanExtra(EXTRA_RESUME, false)) {
            val resumeIndex = prefs.getInt(KEY_INDEX, 0)
            val resumePos = prefs.getLong(KEY_POS, 0L)
            player.seekTo(resumeIndex, resumePos)
        }
        player.playWhenReady = true
    }

    private fun handlePlaySingle(intent: Intent) {
        val uri = intent.getStringExtra(EXTRA_SINGLE_URI)?.let(Uri::parse) ?: return
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    private fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
        prefs.edit { putBoolean(KEY_MUTED, muted) }
    }

    private fun updateNotification(isPlaying: Boolean) {
        val title = if (currentTitle.isBlank()) getString(R.string.no_track) else currentTitle
        val notification = notificationHelper.buildNotification(isPlaying, title)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun sendTrackChanged(uri: Uri?) {
        val intent = Intent(ACTION_TRACK_CHANGED)
        intent.putExtra(EXTRA_TITLE, currentTitle)
        uri?.let {
            extractCoverBase64(it)?.let { b64 -> intent.putExtra(EXTRA_COVER_B64, b64) }
        }
        sendBroadcast(intent)
    }

    private fun extractCoverBase64(uri: Uri): String? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val picture = retriever.embeddedPicture
            retriever.release()
            picture?.let { Base64.encodeToString(it, Base64.DEFAULT) }
        } catch (e: Exception) {
            null
        }
    }

    private fun persistPlaybackState() {
        prefs.edit {
            putInt(KEY_INDEX, player.currentMediaItemIndex)
            putLong(KEY_POS, player.currentPosition)
            putBoolean(KEY_MUTED, muted)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistPlaybackState()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistPlaybackState()
        player.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PlayerService"
        private const val PREFS_NAME = "player_prefs"
        private const val KEY_INDEX = "index"
        private const val KEY_POS = "pos"
        private const val KEY_MUTED = "muted"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_FOLDER = "com.example.ladastyleplayer.action.PLAY_FOLDER"
        const val ACTION_PLAY_SINGLE = "com.example.ladastyleplayer.action.PLAY_SINGLE"
        const val ACTION_PLAY_PAUSE = "com.example.ladastyleplayer.action.PLAY_PAUSE"
        const val ACTION_TOGGLE_MUTE = "com.example.ladastyleplayer.action.TOGGLE_MUTE"
        const val ACTION_NEXT = "com.example.ladastyleplayer.action.NEXT"
        const val ACTION_PREV = "com.example.ladastyleplayer.action.PREV"
        const val ACTION_TRACK_CHANGED = "com.example.ladastyleplayer.action.TRACK_CHANGED"

        const val EXTRA_URI_LIST = "extra_uri_list"
        const val EXTRA_RESUME = "extra_resume"
        const val EXTRA_SINGLE_URI = "extra_single_uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COVER_B64 = "cover_b64"
    }
}
