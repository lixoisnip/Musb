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
    private var currentArtist: String = ""
    private var currentAlbum: String = ""
    private var currentCoverBase64: String? = null
    private var currentUri: Uri? = null
    private var muted = false

    override fun onCreate() {
        super.onCreate()
        notificationHelper = PlayerNotificationHelper(this)
        notificationHelper.ensureChannel()
        player = ExoPlayer.Builder(this).build()
        muted = prefs.getBoolean(KEY_MUTED, false)
        player.volume = if (muted) 0f else 1f
        player.shuffleModeEnabled = prefs.getBoolean(KEY_SHUFFLE, false)
        player.repeatMode = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification(isPlaying)
                persistPlaybackState()
                sendTrackChanged(includeCover = false)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentUri = mediaItem?.localConfiguration?.uri
                val metadata = currentUri?.let { extractMetadata(it) }
                currentTitle = metadata?.title
                    ?: mediaItem?.mediaMetadata?.title?.toString()
                    ?: currentUri?.lastPathSegment
                    ?: getString(R.string.no_track)
                currentArtist = metadata?.artist ?: ""
                currentAlbum = metadata?.album ?: ""
                currentCoverBase64 = metadata?.coverBase64

                sendTrackChanged(includeCover = true)
                updateNotification(player.isPlaying)
                persistPlaybackState()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.d(TAG, "onPlayerError: code=${error.errorCodeName}, message=${error.message}", error)
                sendPlaybackError(error.message ?: getString(R.string.playback_failed))
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
            ACTION_SEEK_TO -> seekTo(intent.getLongExtra(EXTRA_SEEK_POSITION_MS, 0L))
            ACTION_SEEK_FORWARD -> seekForwardBy10Sec()
            ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
            ACTION_CYCLE_REPEAT -> cycleRepeatMode()
            ACTION_TOGGLE_MUTE -> toggleMute()
            ACTION_REPORT_STATE -> sendTrackChanged(includeCover = false)
        }
        return START_STICKY
    }

    private fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        player.seekTo(target)
        sendTrackChanged(includeCover = false)
    }

    private fun seekForwardBy10Sec() {
        val target = (player.currentPosition + 10_000L).coerceAtMost((player.duration).takeIf { it > 0L } ?: Long.MAX_VALUE)
        player.seekTo(target)
        sendTrackChanged(includeCover = false)
    }

    private fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        sendTrackChanged(includeCover = false)
        persistPlaybackState()
    }

    private fun cycleRepeatMode() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        sendTrackChanged(includeCover = false)
        persistPlaybackState()
    }

    private fun handlePlayFolder(intent: Intent) {
        val uris = intent.getStringArrayListExtra(EXTRA_URI_LIST)?.map(Uri::parse).orEmpty()
        if (uris.isEmpty()) {
            sendPlaybackError(getString(R.string.no_supported_audio))
            return
        }
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
        val uri = intent.getStringExtra(EXTRA_SINGLE_URI)?.let(Uri::parse)
        if (uri == null) {
            sendPlaybackError(getString(R.string.playback_failed))
            return
        }
        Log.d(TAG, "handlePlaySingle: uri=$uri")
        runCatching {
            player.setMediaItems(listOf(MediaItem.fromUri(uri)), 0, 0L)
            currentUri = uri
            val metadata = extractMetadata(uri)
            currentTitle = metadata?.title ?: uri.lastPathSegment ?: getString(R.string.no_track)
            currentArtist = metadata?.artist ?: ""
            currentAlbum = metadata?.album ?: ""
            currentCoverBase64 = metadata?.coverBase64
            sendTrackChanged(includeCover = true)
            player.prepare()
            player.playWhenReady = true
            player.play()
        }.onFailure { error ->
            Log.d(TAG, "handlePlaySingle failed for uri=$uri: ${error.message}", error)
            sendPlaybackError(error.message ?: getString(R.string.playback_failed))
        }
    }

    private fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
        prefs.edit { putBoolean(KEY_MUTED, muted) }
        sendTrackChanged(includeCover = false)
    }

    private fun updateNotification(isPlaying: Boolean) {
        val title = if (currentTitle.isBlank()) getString(R.string.no_track) else currentTitle
        val notification = notificationHelper.buildNotification(isPlaying, title)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun sendTrackChanged(includeCover: Boolean) {
        val intent = Intent(ACTION_TRACK_CHANGED)
        intent.putExtra(EXTRA_TITLE, currentTitle)
        intent.putExtra(EXTRA_ARTIST, currentArtist)
        intent.putExtra(EXTRA_ALBUM, currentAlbum)
        intent.putExtra(EXTRA_IS_PLAYING, player.isPlaying)
        intent.putExtra(EXTRA_POSITION_MS, player.currentPosition)
        intent.putExtra(EXTRA_DURATION_MS, player.duration.takeIf { it > 0 } ?: 0L)
        intent.putExtra(EXTRA_CURRENT_URI, currentUri?.toString())
        intent.putExtra(EXTRA_IS_MUTED, muted)
        intent.putExtra(EXTRA_SHUFFLE_ENABLED, player.shuffleModeEnabled)
        intent.putExtra(EXTRA_REPEAT_MODE, player.repeatMode)

        if (includeCover) {
            intent.putExtra(EXTRA_COVER_B64, currentCoverBase64)
        }

        sendBroadcast(intent)
    }

    private fun sendPlaybackError(message: String) {
        val intent = Intent(ACTION_PLAYBACK_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun extractMetadata(uri: Uri): TrackMetadata? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val metadata = TrackMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                coverBase64 = retriever.embeddedPicture?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            )
            retriever.release()
            metadata
        } catch (e: Exception) {
            null
        }
    }

    private fun persistPlaybackState() {
        prefs.edit {
            putInt(KEY_INDEX, player.currentMediaItemIndex)
            putLong(KEY_POS, player.currentPosition)
            putBoolean(KEY_MUTED, muted)
            putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
            putInt(KEY_REPEAT_MODE, player.repeatMode)
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

    data class TrackMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val coverBase64: String?
    )

    companion object {
        private const val TAG = "PlayerService"
        private const val PREFS_NAME = "player_prefs"
        private const val KEY_INDEX = "index"
        private const val KEY_POS = "pos"
        private const val KEY_MUTED = "muted"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_FOLDER = "com.example.ladastyleplayer.action.PLAY_FOLDER"
        const val ACTION_PLAY_SINGLE = "com.example.ladastyleplayer.action.PLAY_SINGLE"
        const val ACTION_PLAY_PAUSE = "com.example.ladastyleplayer.action.PLAY_PAUSE"
        const val ACTION_TOGGLE_MUTE = "com.example.ladastyleplayer.action.TOGGLE_MUTE"
        const val ACTION_NEXT = "com.example.ladastyleplayer.action.NEXT"
        const val ACTION_PREV = "com.example.ladastyleplayer.action.PREV"
        const val ACTION_SEEK_TO = "com.example.ladastyleplayer.action.SEEK_TO"
        const val ACTION_REPORT_STATE = "com.example.ladastyleplayer.action.REPORT_STATE"
        const val ACTION_SEEK_FORWARD = "com.example.ladastyleplayer.action.SEEK_FORWARD"
        const val ACTION_TOGGLE_SHUFFLE = "com.example.ladastyleplayer.action.TOGGLE_SHUFFLE"
        const val ACTION_CYCLE_REPEAT = "com.example.ladastyleplayer.action.CYCLE_REPEAT"
        const val ACTION_TRACK_CHANGED = "com.example.ladastyleplayer.action.TRACK_CHANGED"
        const val ACTION_PLAYBACK_ERROR = "com.example.ladastyleplayer.action.PLAYBACK_ERROR"

        const val EXTRA_URI_LIST = "extra_uri_list"
        const val EXTRA_RESUME = "extra_resume"
        const val EXTRA_SINGLE_URI = "extra_single_uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_CURRENT_URI = "current_uri"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_COVER_B64 = "cover_b64"
        const val EXTRA_SEEK_POSITION_MS = "seek_position_ms"
        const val EXTRA_IS_MUTED = "is_muted"
        const val EXTRA_SHUFFLE_ENABLED = "shuffle_enabled"
        const val EXTRA_REPEAT_MODE = "repeat_mode"
        const val EXTRA_ERROR_MESSAGE = "error_message"
    }
}
