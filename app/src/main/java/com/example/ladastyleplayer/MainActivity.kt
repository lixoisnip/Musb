package com.example.ladastyleplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ladastyleplayer.player.PlayerService
import com.example.ladastyleplayer.utils.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val repository = FileRepository()
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var leftAdapter: FileEntryAdapter
    private lateinit var rightAdapter: FileEntryAdapter

    private lateinit var pathText: TextView
    private lateinit var clockText: TextView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var albumText: TextView
    private lateinit var coverImage: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var playPauseButton: Button

    private var currentFolder: DocumentFile? = null
    private val coverPlaceholderRes = android.R.drawable.ic_menu_report_image

    private val clockRunnable = object : Runnable {
        override fun run() {
            val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
            clockText.text = formatter.format(Date())
            uiHandler.postDelayed(this, CLOCK_REFRESH_MS)
        }
    }

    private val playerProgressPoller = object : Runnable {
        override fun run() {
            startPlayerAction(PlayerService.ACTION_REPORT_STATE)
            uiHandler.postDelayed(this, PLAYER_PROGRESS_POLL_MS)
        }
    }

    private val chooseFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                Toast.makeText(this, R.string.folder_pick_cancelled, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            if (!persistUriPermission(uri)) {
                Toast.makeText(this, R.string.uri_permission_failed, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            prefs.edit { putString(KEY_TREE_URI, uri.toString()) }
            openTree(uri)
        }

    private val chooseAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                Toast.makeText(this, R.string.file_pick_cancelled, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            playSelectedAudio(uri)
        }

    private val trackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlayerService.ACTION_TRACK_CHANGED -> handleTrackChanged(intent)
                PlayerService.ACTION_PLAYBACK_ERROR -> {
                    val reason = intent.getStringExtra(PlayerService.EXTRA_ERROR_MESSAGE)
                        ?: getString(R.string.playback_failed)
                    Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleTrackChanged(intent: Intent) {
        titleText.text = intent.getStringExtra(PlayerService.EXTRA_TITLE) ?: getString(R.string.no_track)
        artistText.text = intent.getStringExtra(PlayerService.EXTRA_ARTIST) ?: getString(R.string.unknown_artist)
        albumText.text = intent.getStringExtra(PlayerService.EXTRA_ALBUM) ?: getString(R.string.unknown_album)
        playPauseButton.text = if (intent.getBooleanExtra(PlayerService.EXTRA_IS_PLAYING, false)) {
            getString(R.string.pause_symbol)
        } else {
            getString(R.string.play_symbol)
        }

        val durationMs = intent.getLongExtra(PlayerService.EXTRA_DURATION_MS, 0L)
        val positionMs = intent.getLongExtra(PlayerService.EXTRA_POSITION_MS, 0L)
        updateProgress(positionMs, durationMs)

        val currentUri = intent.getStringExtra(PlayerService.EXTRA_CURRENT_URI)
        rightAdapter.setHighlightedUri(currentUri)

        val b64 = intent.getStringExtra(PlayerService.EXTRA_COVER_B64)
        if (!b64.isNullOrBlank()) {
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.onSuccess { bitmap ->
                if (bitmap != null) {
                    coverImage.setImageBitmap(bitmap)
                } else {
                    loadCoverFromUri(currentUri)
                }
            }.onFailure {
                loadCoverFromUri(currentUri)
            }
        } else if (intent.hasExtra(PlayerService.EXTRA_COVER_B64)) {
            loadCoverFromUri(currentUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pathText = findViewById(R.id.pathText)
        clockText = findViewById(R.id.clockText)
        titleText = findViewById(R.id.titleText)
        artistText = findViewById(R.id.artistText)
        albumText = findViewById(R.id.albumText)
        coverImage = findViewById(R.id.coverImage)
        progressBar = findViewById(R.id.progressBar)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)
        playPauseButton = findViewById(R.id.playPauseButton)
        coverImage.setImageResource(coverPlaceholderRes)

        setupRecyclerViews()
        setupButtons()

        val persistedTree = prefs.getString(KEY_TREE_URI, null)
        if (persistedTree != null) {
            openTree(Uri.parse(persistedTree), resume = true)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(PlayerService.ACTION_TRACK_CHANGED)
            addAction(PlayerService.ACTION_PLAYBACK_ERROR)
        }
        registerReceiverCompat(trackReceiver, filter)
        uiHandler.post(clockRunnable)
        uiHandler.post(playerProgressPoller)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(clockRunnable)
        uiHandler.removeCallbacks(playerProgressPoller)
        unregisterReceiver(trackReceiver)
        super.onStop()
    }

    private fun setupRecyclerViews() {
        leftAdapter = FileEntryAdapter(
            showPlayFolderButton = true,
            onFolderClick = { folder -> openFolder(folder) },
            onFileClick = { file -> playSingle(file) },
            onPlayFolder = { folder -> playAll(folder, false) }
        )
        rightAdapter = FileEntryAdapter(
            showPlayFolderButton = false,
            onFolderClick = { folder -> openFolder(folder) },
            onFileClick = { file -> playSingle(file) },
            onPlayFolder = { folder -> playAll(folder, false) }
        )

        findViewById<RecyclerView>(R.id.leftRecycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = leftAdapter
        }
        findViewById<RecyclerView>(R.id.rightRecycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = rightAdapter
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.browseButton).setOnClickListener {
            chooseAudioLauncher.launch(arrayOf("audio/*"))
        }
        findViewById<Button>(R.id.browseButton).setOnLongClickListener {
            chooseFolderLauncher.launch(null)
            Toast.makeText(this, R.string.folder_picker_hint, Toast.LENGTH_SHORT).show()
            true
        }
        playPauseButton.setOnClickListener {
            startPlayerAction(PlayerService.ACTION_PLAY_PAUSE)
        }
        findViewById<Button>(R.id.nextButton).setOnClickListener {
            startPlayerAction(PlayerService.ACTION_NEXT)
        }
        findViewById<Button>(R.id.prevButton).setOnClickListener {
            startPlayerAction(PlayerService.ACTION_PREV)
        }
        findViewById<Button>(R.id.muteButton).setOnClickListener {
            startPlayerAction(PlayerService.ACTION_TOGGLE_MUTE)
        }
    }

    private fun openTree(uri: Uri, resume: Boolean = false) {
        try {
            val root = DocumentFile.fromTreeUri(this, uri)
            if (root == null || !root.exists()) {
                showUsbError()
                return
            }
            pathText.text = getString(R.string.status_usb_ready)
            currentFolder = root
            openFolder(root)
            if (resume) playAll(root, true)
        } catch (e: Exception) {
            showUsbError()
        }
    }

    private fun playSelectedAudio(uri: Uri) {
        if (!persistUriPermission(uri)) {
            Toast.makeText(this, R.string.uri_permission_failed, Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, PlayerService::class.java).apply {
            action = PlayerService.ACTION_PLAY_SINGLE
            putExtra(PlayerService.EXTRA_SINGLE_URI, uri.toString())
        }
        startService(intent)
    }

    private fun openFolder(folder: DocumentFile) {
        currentFolder = folder
        leftAdapter.setSelectedUri(folder.uri.toString())
        pathText.text = folder.uri.path ?: getString(R.string.status_usb_ready)

        mainScope.launch {
            runCatching { repository.listChildren(folder) }
                .onSuccess { items ->
                    val folders = items.filter { it.isDirectory }
                    val rightItems = if (folders.isNotEmpty()) {
                        folders
                    } else {
                        items.filter { it.isFile && repository.isSupportedAudio(it.name) }
                    }
                    leftAdapter.submitList(folders)
                    rightAdapter.submitList(rightItems)
                    findViewById<TextView>(R.id.leftEmptyText).text = getString(R.string.no_folders_found)
                    findViewById<TextView>(R.id.rightEmptyText).text = if (folders.isNotEmpty()) {
                        getString(R.string.no_subfolders_found)
                    } else {
                        getString(R.string.no_supported_audio)
                    }
                    findViewById<TextView>(R.id.leftEmptyText).visibility =
                        if (folders.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                    findViewById<TextView>(R.id.rightEmptyText).visibility =
                        if (rightItems.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
                .onFailure {
                    showUsbError()
                    findViewById<TextView>(R.id.leftEmptyText).visibility = android.view.View.VISIBLE
                    findViewById<TextView>(R.id.rightEmptyText).visibility = android.view.View.VISIBLE
                }
        }
    }

    private fun loadCoverFromUri(uriString: String?) {
        if (uriString.isNullOrBlank()) {
            coverImage.setImageResource(coverPlaceholderRes)
            return
        }
        mainScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(this@MainActivity, Uri.parse(uriString))
                    val imageBytes = retriever.embeddedPicture
                    retriever.release()
                    imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }.getOrNull()
            }

            if (bitmap != null) {
                coverImage.setImageBitmap(bitmap)
            } else {
                coverImage.setImageResource(coverPlaceholderRes)
            }
        }
    }

    private fun playSingle(file: DocumentFile) {
        val intent = Intent(this, PlayerService::class.java).apply {
            action = PlayerService.ACTION_PLAY_SINGLE
            putExtra(PlayerService.EXTRA_SINGLE_URI, file.uri.toString())
        }
        startService(intent)
    }

    private fun playAll(folder: DocumentFile, resume: Boolean) {
        mainScope.launch {
            val files = runCatching { repository.collectAudioRecursive(folder) }.getOrElse {
                showUsbError()
                return@launch
            }
            val uriStrings = ArrayList(files.map { it.uri.toString() })
            if (uriStrings.isEmpty()) return@launch
            val intent = Intent(this@MainActivity, PlayerService::class.java).apply {
                action = PlayerService.ACTION_PLAY_FOLDER
                putStringArrayListExtra(PlayerService.EXTRA_URI_LIST, uriStrings)
                putExtra(PlayerService.EXTRA_RESUME, resume)
            }
            startService(intent)
        }
    }

    private fun startPlayerAction(action: String) {
        startService(Intent(this, PlayerService::class.java).setAction(action))
    }

    private fun persistUriPermission(uri: Uri): Boolean {
        return try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun updateProgress(positionMs: Long, durationMs: Long) {
        val safeDuration = if (durationMs > 0) durationMs else 1L
        progressBar.progress = ((positionMs.coerceAtLeast(0L) * 1000L) / safeDuration).toInt()
        currentTimeText.text = formatTime(positionMs)
        totalTimeText.text = if (durationMs > 0) formatTime(durationMs) else "00:00"
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = (timeMs / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun showUsbError() {
        pathText.text = getString(R.string.status_no_usb)
        Toast.makeText(this, R.string.usb_disconnected, Toast.LENGTH_LONG).show()
    }

    @Suppress("DEPRECATION")
    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    companion object {
        private const val PREFS_NAME = "player_prefs"
        private const val KEY_TREE_URI = "tree_uri"
        private const val CLOCK_REFRESH_MS = 30_000L
        private const val PLAYER_PROGRESS_POLL_MS = 1_000L
    }
}
