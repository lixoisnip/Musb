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
import android.provider.DocumentsContract
import android.util.Log
import android.util.Base64
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.Player
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
    data class SourceRoot(
        val treeUri: Uri,
        val label: String,
        val isAvailable: Boolean
    )

    private enum class LeftPanelMode {
        SOURCES,
        BRANCHES
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val repository = FileRepository()
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var leftAdapter: FileEntryAdapter
    private lateinit var rightAdapter: FileEntryAdapter

    private lateinit var pathText: TextView
    private lateinit var topSourceText: TextView
    private lateinit var clockText: TextView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var albumText: TextView
    private lateinit var coverImage: ImageView
    private lateinit var progressBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var seekButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var listButton: ImageButton
    private lateinit var infoButton: ImageButton
    private lateinit var muteButton: ImageButton
    private lateinit var usbStatusText: TextView
    private lateinit var rightRecycler: RecyclerView
    private lateinit var rightPanel: View

    private val coverPlaceholderRes = android.R.drawable.ic_menu_report_image
    private var latestDurationMs: Long = 0L
    private var isUserSeeking = false
    private var lastCoverUri: String? = null
    private var currentTrackUri: String? = null
    private var isPlaylistFocused = false
    private var isShuffleOn = false
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var isMuted = false
    private var sourceRoots: List<SourceRoot> = emptyList()
    private var selectedSourceRoot: SourceRoot? = null
    private var selectedLeftKey: String? = null
    private var selectedLeftFolder: DocumentFile? = null
    private var currentRightTrackScope: DocumentFile? = null
    private var leftPanelMode: LeftPanelMode = LeftPanelMode.SOURCES
    private var singleFileModeActive: Boolean = false
    private var currentBreadcrumb: String = "Music"

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
            Log.d(TAG, "chooseFolderLauncher result uri=$uri")
            if (uri == null) {
                Toast.makeText(this, R.string.folder_pick_cancelled, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            openTree(uri)
        }

    private val chooseAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            Log.d(TAG, "chooseAudioLauncher result uri=$uri")
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
        topSourceText.text = "Playing from ${albumText.text}"

        val isPlaying = intent.getBooleanExtra(PlayerService.EXTRA_IS_PLAYING, false)
        playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_transport_pause else R.drawable.ic_transport_play)

        isMuted = intent.getBooleanExtra(PlayerService.EXTRA_IS_MUTED, isMuted)
        isShuffleOn = intent.getBooleanExtra(PlayerService.EXTRA_SHUFFLE_ENABLED, isShuffleOn)
        repeatMode = intent.getIntExtra(PlayerService.EXTRA_REPEAT_MODE, repeatMode)
        applyControlStates()

        val durationMs = intent.getLongExtra(PlayerService.EXTRA_DURATION_MS, 0L)
        val positionMs = intent.getLongExtra(PlayerService.EXTRA_POSITION_MS, 0L)
        updateProgress(positionMs, durationMs)

        currentTrackUri = intent.getStringExtra(PlayerService.EXTRA_CURRENT_URI)
        rightAdapter.setHighlightedUri(currentTrackUri)

        val b64 = intent.getStringExtra(PlayerService.EXTRA_COVER_B64)
        var didSetCoverFromBroadcast = false
        if (!b64.isNullOrBlank()) {
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.onSuccess { bitmap ->
                if (bitmap != null) {
                    coverImage.setImageBitmap(bitmap)
                    lastCoverUri = currentTrackUri
                    didSetCoverFromBroadcast = true
                } else {
                    loadCoverFromUri(currentTrackUri)
                }
            }.onFailure {
                loadCoverFromUri(currentTrackUri)
            }
        } else if (currentTrackUri != lastCoverUri || isCoverPlaceholderVisible()) {
            loadCoverFromUri(currentTrackUri)
        }

        if (!didSetCoverFromBroadcast && b64.isNullOrBlank() && currentTrackUri == null) {
            coverImage.setImageResource(coverPlaceholderRes)
            lastCoverUri = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pathText = findViewById(R.id.pathText)
        topSourceText = findViewById(R.id.topSourceText)
        clockText = findViewById(R.id.clockText)
        titleText = findViewById(R.id.titleText)
        artistText = findViewById(R.id.artistText)
        albumText = findViewById(R.id.albumText)
        coverImage = findViewById(R.id.coverImage)
        progressBar = findViewById(R.id.progressBar)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)
        playPauseButton = findViewById(R.id.playPauseButton)
        seekButton = findViewById(R.id.seekButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        repeatButton = findViewById(R.id.repeatButton)
        listButton = findViewById(R.id.listButton)
        infoButton = findViewById(R.id.commentButton)
        muteButton = findViewById(R.id.muteButton)
        usbStatusText = findViewById(R.id.usbStatusText)
        rightPanel = findViewById(R.id.rightPanel)
        rightRecycler = findViewById(R.id.rightRecycler)

        coverImage.setImageResource(coverPlaceholderRes)
        progressBar.max = 1000
        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || !isUserSeeking) return
                val previewPositionMs = if (latestDurationMs > 0L) {
                    (latestDurationMs * progress.toLong()) / progressBar.max.toLong()
                } else {
                    0L
                }
                currentTimeText.text = formatTime(previewPositionMs)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                val seekPositionMs = if (latestDurationMs > 0L) {
                    (latestDurationMs * progress.toLong()) / progressBar.max.toLong()
                } else {
                    0L
                }
                isUserSeeking = false
                startService(Intent(this@MainActivity, PlayerService::class.java).apply {
                    action = PlayerService.ACTION_SEEK_TO
                    putExtra(PlayerService.EXTRA_SEEK_POSITION_MS, seekPositionMs)
                })
                currentTimeText.text = formatTime(seekPositionMs)
            }
        })

        setupRecyclerViews()
        setupButtons()
        applyControlStates()

        loadPersistedSources()
        showSourceRootsView()
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
            showPlayFolderButton = false,
            onFolderClick = { folder ->
                selectLeftItem(folder.uri.toString())
                openBranch(folder)
            },
            onFileClick = { file -> playSingle(file) },
            onPlayFolder = { _ -> },
            onCustomClick = { sourceKey -> onSourceSelected(sourceKey) },
            onUpClick = { showSourceRootsView() }
        )
        rightAdapter = FileEntryAdapter(
            showPlayFolderButton = false,
            onFolderClick = { _ -> },
            onFileClick = { file -> playSingle(file) },
            onPlayFolder = { _ -> }
        )

        findViewById<RecyclerView>(R.id.leftRecycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = leftAdapter
        }
        rightRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = rightAdapter
        }
    }

    private fun setupButtons() {
        findViewById<ImageButton>(R.id.browseButton).setOnClickListener {
            Log.d(TAG, "browseButton click: launching single audio picker")
            chooseAudioLauncher.launch(arrayOf("audio/*"))
            Toast.makeText(this, R.string.file_picker_hint, Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.browseButton).setOnLongClickListener {
            Log.d(TAG, "browseButton long-click: launching folder tree picker")
            chooseFolderLauncher.launch(null)
            Toast.makeText(this, R.string.folder_picker_hint, Toast.LENGTH_SHORT).show()
            true
        }
        playPauseButton.setOnClickListener {
            startPlayerAction(PlayerService.ACTION_PLAY_PAUSE)
        }
        findViewById<ImageButton>(R.id.nextButton).setOnClickListener {
            startPlayerAction(PlayerService.ACTION_NEXT)
        }
        findViewById<ImageButton>(R.id.prevButton).setOnClickListener {
            startPlayerAction(PlayerService.ACTION_PREV)
        }
        muteButton.setOnClickListener {
            startPlayerAction(PlayerService.ACTION_TOGGLE_MUTE)
        }
        seekButton.setOnClickListener {
            startPlayerAction(PlayerService.ACTION_SEEK_FORWARD)
            Toast.makeText(this, R.string.seek_forward_hint, Toast.LENGTH_SHORT).show()
        }
        shuffleButton.setOnClickListener {
            startPlayerAction(PlayerService.ACTION_TOGGLE_SHUFFLE)
        }
        repeatButton.setOnClickListener {
            startPlayerAction(PlayerService.ACTION_CYCLE_REPEAT)
        }
        listButton.setOnClickListener {
            isPlaylistFocused = !isPlaylistFocused
            rightPanel.isActivated = isPlaylistFocused
            listButton.isActivated = isPlaylistFocused
            scrollPlaylistToCurrent()
        }
        infoButton.setOnClickListener {
            showTrackInfoDialog()
        }
    }

    private fun registerSourceRoot(uri: Uri) {
        val root = DocumentFile.fromTreeUri(this, uri)
        val label = repository.suggestSourceLabel(root?.name, uri.toString())
        saveSourceLabel(uri, label)
        saveSourceUri(uri)
        loadPersistedSources(selectedUri = uri.toString())
        enterSource(uri.toString())
    }

    private fun openTree(uri: Uri) {
        Log.d(TAG, "openTree(uri=$uri)")
        if (!persistUriPermission(uri)) {
            Log.d(TAG, "openTree denied: persistUriPermission failed uri=$uri")
            Toast.makeText(this, R.string.source_root_denied, Toast.LENGTH_LONG).show()
            return
        }

        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.exists() || !root.isDirectory) {
            Log.d(TAG, "openTree denied: invalid root uri=$uri exists=${root?.exists()} isDirectory=${root?.isDirectory}")
            Toast.makeText(this, R.string.source_root_denied, Toast.LENGTH_LONG).show()
            return
        }

        mainScope.launch {
            val canListChildren = runCatching {
                repository.listChildren(root)
                true
            }.getOrElse { error ->
                Log.d(TAG, "openTree denied: cannot list root uri=$uri message=${error.message}")
                false
            }

            if (!canListChildren) {
                Toast.makeText(this@MainActivity, R.string.source_root_denied, Toast.LENGTH_LONG).show()
                return@launch
            }

            registerSourceRoot(uri)
        }
    }

    private fun loadPersistedSources(selectedUri: String? = null) {
        val storedUris = prefs.getStringSet(KEY_SOURCE_URIS, emptySet())?.toList().orEmpty()
        sourceRoots = storedUris.map { uriString ->
            val parsed = Uri.parse(uriString)
            val root = DocumentFile.fromTreeUri(this, parsed)
            val available = root != null && root.exists()
            val savedLabel = prefs.getString(sourceLabelKey(uriString), null)
            val label = savedLabel ?: repository.suggestSourceLabel(root?.name, uriString)
            SourceRoot(parsed, label, available)
        }.sortedBy { it.label.lowercase(Locale.ROOT) }
        val selected = selectedUri ?: prefs.getString(KEY_SELECTED_SOURCE_URI, null)
        selectedSourceRoot = sourceRoots.firstOrNull { it.treeUri.toString() == selected }
    }

    private fun showSourceRootsView() {
        leftPanelMode = LeftPanelMode.SOURCES
        singleFileModeActive = false
        selectedLeftFolder = null
        currentRightTrackScope = null
        currentBreadcrumb = getString(R.string.sources)
        pathText.text = getString(R.string.path_sources)
        topSourceText.text = getString(R.string.top_source_roots)
        usbStatusText.text = if (sourceRoots.isEmpty()) getString(R.string.status_no_sources) else getString(R.string.status_usb_connected)
        renderSourcesOnLeft()
        rightAdapter.submitList(emptyList())
        findViewById<TextView>(R.id.rightEmptyText).text = getString(R.string.select_source_hint)
        findViewById<TextView>(R.id.rightEmptyText).visibility = View.VISIBLE
    }

    private fun renderSourcesOnLeft() {
        val sourceItems = sourceRoots.map { source ->
            FileEntryAdapter.EntryItem(
                customId = sourceKey(source.treeUri.toString()),
                customName = if (source.isAvailable) source.label else "${source.label} (${getString(R.string.source_unavailable_suffix)})",
                customIcon = "\uD83D\uDCC1",
                isCustomFolder = true,
                isEnabled = source.isAvailable
            )
        }
        leftAdapter.submitList(sourceItems)
        selectLeftItem(selectedSourceRoot?.let { sourceKey(it.treeUri.toString()) })
        findViewById<TextView>(R.id.leftEmptyText).text = getString(R.string.add_music_source)
        findViewById<TextView>(R.id.leftEmptyText).visibility = if (sourceItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onSourceSelected(key: String) {
        val treeUri = key.removePrefix(SOURCE_KEY_PREFIX)
        enterSource(treeUri)
    }

    private fun enterSource(sourceUriString: String) {
        val source = sourceRoots.firstOrNull { it.treeUri.toString() == sourceUriString } ?: return
        if (!source.isAvailable) return
        selectedSourceRoot = source
        prefs.edit { putString(KEY_SELECTED_SOURCE_URI, sourceUriString) }
        refreshMusicExplorerForSource(source)
    }

    private fun playSelectedAudio(uri: Uri) {
        Log.d(TAG, "playSelectedAudio requested uri=$uri")
        val persistResult = persistUriPermission(uri)
        Log.d(TAG, "playSelectedAudio persistUriPermission(uri=$uri)=$persistResult")
        if (!persistResult) {
            Log.d(TAG, "playSelectedAudio continuing without persisted permission for uri=$uri")
        }

        val intent = Intent(this, PlayerService::class.java).apply {
            action = PlayerService.ACTION_PLAY_SINGLE
            putExtra(PlayerService.EXTRA_SINGLE_URI, uri.toString())
        }
        Log.d(TAG, "Dispatching ACTION_PLAY_SINGLE with EXTRA_SINGLE_URI=$uri")
        startService(intent)

        currentTrackUri = uri.toString()
        rightAdapter.setHighlightedUri(currentTrackUri)

        mainScope.launch {
            try {
                tryRestoreExplorerContextFromPickedFile(uri)
            } catch (error: Exception) {
                Log.d(
                    TAG,
                    "playSelectedAudio optional explorer restore failed for uri=$uri: ${error.message}"
                )
            }
        }
    }

    private suspend fun tryRestoreExplorerContextFromPickedFile(uri: Uri) {
        try {
            val containingFolder = resolveContainingFolderFromPickedFile(uri)
                ?: resolveParentFolderFromSingleDocument(uri)
            if (containingFolder != null) {
                Log.d(
                    TAG,
                    "playSelectedAudio optional explorer restore success containingFolderUri=${containingFolder.uri}"
                )
                alignExplorerToFolder(containingFolder)
            } else {
                Log.d(
                    TAG,
                    "playSelectedAudio optional explorer restore skipped: containing folder unresolved for uri=$uri"
                )
                Toast.makeText(this, R.string.file_context_unavailable, Toast.LENGTH_SHORT).show()
            }
        } catch (error: Exception) {
            Log.d(
                TAG,
                "playSelectedAudio optional explorer restore failed for uri=$uri: ${error.message}"
            )
            Toast.makeText(this, R.string.file_context_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun resolveParentFolderFromSingleDocument(fileUri: Uri): DocumentFile? {
        val authority = fileUri.authority ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull() ?: return null
        val separatorIndex = docId.lastIndexOf('/')
        if (separatorIndex <= 0) return null
        val parentDocId = docId.substring(0, separatorIndex)
        val parentUri = DocumentsContract.buildDocumentUri(authority, parentDocId)
        val folder = DocumentFile.fromSingleUri(this, parentUri) ?: return null
        if (!folder.exists() || !folder.isDirectory) return null
        val canListChildren = runCatching {
            repository.listChildren(folder)
            true
        }.getOrDefault(false)
        Log.d(TAG, "resolveParentFolderFromSingleDocument uri=$fileUri parentUri=$parentUri canListChildren=$canListChildren")
        return if (canListChildren) folder else null
    }

    private fun refreshMusicExplorerForSource(source: SourceRoot) {
        val root = DocumentFile.fromTreeUri(this, source.treeUri)
        Log.d(TAG, "refreshMusicExplorer(root=${source.treeUri})")
        if (root == null || !root.exists()) {
            showUsbError()
            showSourceRootsView()
            return
        }
        singleFileModeActive = false
        leftPanelMode = LeftPanelMode.BRANCHES
        usbStatusText.text = getString(R.string.status_usb_connected)
        renderPanelsForSelectedFolder(selectedFolder = root, source = source, selectedTrackUri = currentTrackUri)
    }

    private fun openBranch(folder: DocumentFile) {
        Log.d(TAG, "selected left folder uri=${folder.uri}")
        selectedLeftFolder = folder
        currentRightTrackScope = folder
        selectLeftItem(folder.uri.toString())
        val source = selectedSourceRoot
        if (source != null) {
            renderPanelsForSelectedFolder(folder, source, currentTrackUri)
        } else {
            renderStandaloneFolderContext(folder, currentTrackUri)
        }
    }

    private fun renderPanelsForSelectedFolder(
        selectedFolder: DocumentFile,
        source: SourceRoot,
        selectedTrackUri: String?
    ) {
        mainScope.launch {
            val root = DocumentFile.fromTreeUri(this@MainActivity, source.treeUri)
            if (root == null || !root.exists()) {
                showUsbError()
                return@launch
            }

            val folderTree = runCatching { repository.collectFolderTree(root) }.getOrElse {
                Log.d(TAG, "renderPanelsForSelectedFolder tree failure root=${source.treeUri}: ${it.message}")
                showUsbError()
                return@launch
            }
            val effectiveFolder = folderTree.firstOrNull { it.uri == selectedFolder.uri } ?: selectedFolder
            selectedLeftFolder = effectiveFolder
            currentRightTrackScope = effectiveFolder
            val breadcrumb = buildBreadcrumb(effectiveFolder, source.treeUri)
            currentBreadcrumb = breadcrumb
            pathText.text = getString(R.string.path_source_branch, source.label, breadcrumb)
            topSourceText.text = getString(R.string.showing_music_in, breadcrumb)

            val leftItems = folderTree.map { FileEntryAdapter.EntryItem(documentFile = it) }
            leftAdapter.submitList(leftItems)
            selectLeftItem(effectiveFolder.uri.toString())
            findViewById<TextView>(R.id.leftEmptyText).text = getString(R.string.no_folders_found)
            findViewById<TextView>(R.id.leftEmptyText).visibility = if (leftItems.isEmpty()) View.VISIBLE else View.GONE
            renderRightTracksForSelectedFolder(effectiveFolder, selectedTrackUri)
        }
    }

    private fun renderRightTracksForSelectedFolder(folder: DocumentFile, selectedTrackUri: String?) {
        mainScope.launch {
            runCatching { repository.collectSupportedAudioRecursively(folder) }
                .onSuccess { tracks ->
                    val rightItems = tracks.map { FileEntryAdapter.EntryItem(documentFile = it) }
                    rightAdapter.submitList(rightItems)
                    rightAdapter.setHighlightedUri(selectedTrackUri)
                    val wasHighlighted = rightAdapter.findPositionByUri(selectedTrackUri) != RecyclerView.NO_POSITION
                    Log.d(
                        TAG,
                        "right-panel track count=${tracks.size} selectedLeftFolderUri=${folder.uri} highlighted=$wasHighlighted"
                    )
                    findViewById<TextView>(R.id.rightEmptyText).text = getString(R.string.no_supported_audio)
                    findViewById<TextView>(R.id.rightEmptyText).visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
                }
                .onFailure {
                    Log.d(TAG, "renderRightTracksForSelectedFolder failed folder=${folder.uri}: ${it.message}")
                    findViewById<TextView>(R.id.rightEmptyText).visibility = View.VISIBLE
                }
        }
    }

    private suspend fun resolveContainingFolderFromPickedFile(fileUri: Uri): DocumentFile? {
        val treeUri = selectedSourceRoot?.treeUri ?: return null
        val authority = fileUri.authority
        if (authority.isNullOrBlank() || authority != treeUri.authority) {
            Log.d(TAG, "resolveContainingFolderFromPickedFile skipped: authority mismatch tree=${treeUri.authority} file=$authority uri=$fileUri")
            return null
        }

        val docId = runCatching { DocumentsContract.getDocumentId(fileUri) }
            .onFailure { Log.d(TAG, "resolveContainingFolderFromPickedFile no documentId for uri=$fileUri: ${it.message}") }
            .getOrNull()
            ?: return null
        val separatorIndex = docId.lastIndexOf('/')
        if (separatorIndex <= 0) {
            Log.d(TAG, "resolveContainingFolderFromPickedFile no parent docId for docId=$docId")
            return null
        }
        val parentDocId = docId.substring(0, separatorIndex)
        Log.d(TAG, "resolveContainingFolderFromPickedFile docId=$docId parentDocId=$parentDocId")

        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val folder = DocumentFile.fromTreeUri(this, parentUri) ?: return null
        if (!folder.exists() || !folder.isDirectory) {
            Log.d(
                TAG,
                "resolveContainingFolderFromPickedFile rejected tree candidate uri=$parentUri exists=${folder.exists()} isDirectory=${folder.isDirectory}"
            )
            return null
        }

        val canListChildren = try {
            repository.listChildren(folder)
            true
        } catch (error: Exception) {
            Log.d(
                TAG,
                "resolveContainingFolderFromPickedFile failed listing candidate uri=$parentUri: ${error.message}"
            )
            false
        }
        Log.d(
            TAG,
            "resolveContainingFolderFromPickedFile tree candidate uri=$parentUri canListChildren=$canListChildren"
        )
        if (!canListChildren) return null

        Log.d(
            TAG,
            "resolveContainingFolderFromPickedFile success for uri=$fileUri parentUri=${folder.uri}"
        )
        return folder
    }

    private fun isDescendantOfBranch(candidate: DocumentFile, branch: DocumentFile): Boolean {
        val branchDocId = runCatching { DocumentsContract.getDocumentId(branch.uri) }.getOrNull() ?: return false
        val candidateDocId = runCatching { DocumentsContract.getDocumentId(candidate.uri) }.getOrNull() ?: return false
        return candidateDocId == branchDocId || candidateDocId.startsWith("$branchDocId/")
    }

    private fun buildBreadcrumb(folder: DocumentFile, treeUri: Uri): String {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return folder.name ?: "Music"
        val currentDocId = runCatching { DocumentsContract.getDocumentId(folder.uri) }.getOrNull()
            ?: return folder.name ?: "Music"
        if (currentDocId == rootDocId) return folder.name ?: "Music"
        val relative = currentDocId.removePrefix("$rootDocId/")
        return relative.ifBlank { folder.name ?: "Music" }.replace("/", " • ")
    }

    private suspend fun alignExplorerToFolder(folder: DocumentFile) {
        Log.d(TAG, "resolved containing folder uri=${folder.uri}")
        val source = selectedSourceRoot
        if (source != null) {
            val root = DocumentFile.fromTreeUri(this, source.treeUri)
            if (root != null && isDescendantOfBranch(folder, root)) {
                renderPanelsForSelectedFolder(folder, source, currentTrackUri)
                return
            }
        }
        renderStandaloneFolderContext(folder, currentTrackUri)
    }

    private fun renderStandaloneFolderContext(folder: DocumentFile, selectedTrackUri: String?) {
        singleFileModeActive = true
        leftPanelMode = LeftPanelMode.BRANCHES
        selectedLeftFolder = folder
        currentRightTrackScope = folder
        currentBreadcrumb = folder.name ?: "Music"
        pathText.text = currentBreadcrumb
        topSourceText.text = getString(R.string.showing_music_in, currentBreadcrumb)
        usbStatusText.text = getString(R.string.status_usb_connected)
        leftAdapter.submitList(listOf(FileEntryAdapter.EntryItem(documentFile = folder)))
        selectLeftItem(folder.uri.toString())
        findViewById<TextView>(R.id.leftEmptyText).visibility = View.GONE
        renderRightTracksForSelectedFolder(folder, selectedTrackUri)
    }

    private fun selectLeftItem(key: String?) {
        selectedLeftKey = key
        leftAdapter.setSelectedKey(key)
    }

    private fun loadCoverFromUri(uriString: String?) {
        if (uriString.isNullOrBlank()) {
            coverImage.setImageResource(coverPlaceholderRes)
            lastCoverUri = null
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
                lastCoverUri = uriString
            } else {
                coverImage.setImageResource(coverPlaceholderRes)
                lastCoverUri = uriString
            }
        }
    }

    private fun playSingle(file: DocumentFile) {
        Log.d(TAG, "playSingle requested: uri=${file.uri}")
        currentTrackUri = file.uri.toString()
        rightAdapter.setHighlightedUri(currentTrackUri)
        val intent = Intent(this, PlayerService::class.java).apply {
            action = PlayerService.ACTION_PLAY_SINGLE
            putExtra(PlayerService.EXTRA_SINGLE_URI, file.uri.toString())
        }
        Log.d(
            TAG,
            "Dispatching ACTION_PLAY_SINGLE with EXTRA_SINGLE_URI=${file.uri}"
        )
        startService(intent)
    }

    private fun playAll(folder: DocumentFile, resume: Boolean) {
        mainScope.launch {
            val files = runCatching { repository.collectSupportedAudioRecursively(folder) }.getOrElse {
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
        return runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.recoverCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            true
        }.getOrElse { error ->
            Log.d(TAG, "persistUriPermission failed for uri=$uri: ${error.message}")
            false
        }
    }

    private fun saveSourceUri(uri: Uri) {
        val current = prefs.getStringSet(KEY_SOURCE_URIS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(uri.toString())
        prefs.edit { putStringSet(KEY_SOURCE_URIS, current) }
    }

    private fun saveSourceLabel(uri: Uri, label: String) {
        prefs.edit { putString(sourceLabelKey(uri.toString()), label) }
    }

    private fun sourceLabelKey(uriString: String): String {
        return "${KEY_SOURCE_LABEL_PREFIX}${uriString.hashCode()}"
    }

    private fun sourceKey(uriString: String): String {
        return "$SOURCE_KEY_PREFIX$uriString"
    }

    private fun updateProgress(positionMs: Long, durationMs: Long) {
        latestDurationMs = durationMs
        val safeDuration = if (durationMs > 0) durationMs else 1L
        if (!isUserSeeking) {
            progressBar.progress = ((positionMs.coerceAtLeast(0L) * 1000L) / safeDuration).toInt()
            currentTimeText.text = formatTime(positionMs)
        }
        totalTimeText.text = if (durationMs > 0) formatTime(durationMs) else "00:00"
    }

    private fun scrollPlaylistToCurrent() {
        val target = rightAdapter.findPositionByUri(currentTrackUri)
        if (target != RecyclerView.NO_POSITION) {
            rightRecycler.smoothScrollToPosition(target)
        }
    }

    private fun showTrackInfoDialog() {
        val trackFile = rightAdapter.getItemByUri(currentTrackUri)?.name
            ?: currentTrackUri?.substringAfterLast('/')
            ?: getString(R.string.unknown_file)
        val body = getString(
            R.string.track_info_body,
            titleText.text.toString().ifBlank { getString(R.string.no_track) },
            artistText.text.toString().ifBlank { getString(R.string.unknown_artist) },
            albumText.text.toString().ifBlank { getString(R.string.unknown_album) },
            trackFile,
            formatTime(latestDurationMs)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.track_info_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun applyControlStates() {
        shuffleButton.isActivated = isShuffleOn
        repeatButton.isActivated = repeatMode != Player.REPEAT_MODE_OFF
        repeatButton.setImageResource(
            if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_transport_repeat_one else R.drawable.ic_transport_repeat
        )
        listButton.isActivated = isPlaylistFocused
        rightPanel.isActivated = isPlaylistFocused
        muteButton.isActivated = isMuted
        muteButton.setImageResource(if (isMuted) R.drawable.ic_transport_volume_off else R.drawable.ic_transport_volume_on)
    }

    private fun isCoverPlaceholderVisible(): Boolean {
        return coverImage.drawable?.constantState == getDrawable(coverPlaceholderRes)?.constantState
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = (timeMs / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun showUsbError() {
        pathText.text = getString(R.string.status_no_usb)
        topSourceText.text = getString(R.string.top_source_placeholder)
        usbStatusText.text = getString(R.string.status_no_usb)
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
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "player_prefs"
        private const val KEY_SOURCE_URIS = "source_uris"
        private const val KEY_SELECTED_SOURCE_URI = "selected_source_uri"
        private const val KEY_SOURCE_LABEL_PREFIX = "source_label_"
        private const val SOURCE_KEY_PREFIX = "source:"
        private const val CLOCK_REFRESH_MS = 30_000L
        private const val PLAYER_PROGRESS_POLL_MS = 1_000L
    }
}
