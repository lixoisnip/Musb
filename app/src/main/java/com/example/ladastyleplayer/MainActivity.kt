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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
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
    private lateinit var leftRecycler: RecyclerView
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
    private var navigationTreeUri: Uri? = null
    private var pendingSourceIdForPicker: String? = null
    private var currentSourceId: String? = null
    private var currentSourceLabel: String? = null
    private var selectedLeftKey: String? = null
    private var selectedLeftFolder: DocumentFile? = null
    private var selectedRightFolder: DocumentFile? = null
    private val expandedRightFolderUris = linkedSetOf<String>()
    private var currentRightTrackScope: DocumentFile? = null
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
                pendingSourceIdForPicker = null
                Toast.makeText(this, R.string.folder_pick_cancelled, Toast.LENGTH_SHORT).show()
                resetToStartupSourceList()
                return@registerForActivityResult
            }
            openPickedSourceTree(uri)
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
        leftAdapter.setHighlightedUri(currentTrackUri)

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

        loadPersistedTreeContext()
        syncInitialFolderContext()
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
            onFolderClick = { _ -> },
            onFileClick = { file -> playSingle(file) },
            onPlayFolder = { _ -> },
            onUpClick = null,
            hierarchicalIndent = false
        )

        rightAdapter = FileEntryAdapter(
            showPlayFolderButton = false,
            onFolderClick = { folder -> onRightFolderTapped(folder) },
            onFileClick = { _ -> },
            onPlayFolder = { _ -> },
            onCustomClick = { sourceId -> onStartupSourceTapped(sourceId) },
            onUpClick = null,
            hierarchicalIndent = true
        )

        leftRecycler = findViewById<RecyclerView>(R.id.leftRecycler).apply {
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

    private fun openPickedSourceTree(uri: Uri) {
        Log.d(TAG, "openPickedSourceTree(uri=$uri)")
        if (!persistUriPermission(uri)) {
            pendingSourceIdForPicker = null
            Log.d(TAG, "openPickedSourceTree denied: persistUriPermission failed uri=$uri")
            Toast.makeText(this, R.string.source_root_denied, Toast.LENGTH_LONG).show()
            resetToStartupSourceList()
            return
        }

        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.exists() || !root.isDirectory) {
            pendingSourceIdForPicker = null
            Log.d(TAG, "openPickedSourceTree denied: invalid root uri=$uri exists=${root?.exists()} isDirectory=${root?.isDirectory}")
            Toast.makeText(this, R.string.source_root_denied, Toast.LENGTH_LONG).show()
            resetToStartupSourceList()
            return
        }

        mainScope.launch {
            val canListChildren = runCatching {
                repository.listChildren(root)
                true
            }.getOrElse { error ->
                Log.d(TAG, "openPickedSourceTree denied: cannot list root uri=$uri message=${error.message}")
                false
            }

            if (!canListChildren) {
                pendingSourceIdForPicker = null
                Toast.makeText(this@MainActivity, R.string.source_root_denied, Toast.LENGTH_LONG).show()
                resetToStartupSourceList()
                return@launch
            }

            navigationTreeUri = uri
            val sourceId = pendingSourceIdForPicker
            sourceId?.let {
                prefs.edit { putString(sourcePrefKey(sourceId), uri.toString()) }
                Log.d(TAG, "Stored source root uri for sourceId=$sourceId uri=$uri")
            }
            currentSourceId = sourceId
            currentSourceLabel = sourceId?.let { sourceLabelForId(it) }
            pendingSourceIdForPicker = null
            renderRootOverview(root)
        }
    }

    private fun openSavedSourceTree(sourceId: String, uri: Uri) {
        Log.d(TAG, "openSavedSourceTree sourceId=$sourceId uri=$uri")
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.exists() || !root.isDirectory) {
            prefs.edit { remove(sourcePrefKey(sourceId)) }
            Toast.makeText(this, R.string.source_root_denied, Toast.LENGTH_LONG).show()
            resetToStartupSourceList()
            return
        }
        mainScope.launch {
            val canListChildren = runCatching {
                repository.listChildren(root)
                true
            }.getOrElse { false }
            if (!canListChildren) {
                prefs.edit { remove(sourcePrefKey(sourceId)) }
                Toast.makeText(this@MainActivity, R.string.source_root_denied, Toast.LENGTH_LONG).show()
                resetToStartupSourceList()
                return@launch
            }
            navigationTreeUri = uri
            pendingSourceIdForPicker = null
            currentSourceId = sourceId
            currentSourceLabel = sourceLabelForId(sourceId)
            renderRootOverview(root)
        }
    }

    private fun loadPersistedTreeContext() {
        // Disabled intentionally for now.
        // We do not restore the last saved folder into UI on startup,
        // because navigation must be driven only by the right panel selection.
        navigationTreeUri = null
    }

    private fun playSelectedAudio(uri: Uri) {
        Log.d(TAG, "playSelectedAudio requested uri=$uri selectedRootUri=$navigationTreeUri selectedRootIsNull=${navigationTreeUri == null}")
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
        leftAdapter.setHighlightedUri(currentTrackUri)

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
            Log.d(
                TAG,
                "tryRestoreExplorerContextFromPickedFile containingFolderResolved=${containingFolder != null} uri=$uri"
            )
            if (containingFolder != null) {
                Log.d(
                    TAG,
                    "playSelectedAudio optional explorer restore success containingFolderUri=${containingFolder.uri}"
                )
                alignExplorerToFolder(containingFolder)
                return
            }

            val fallbackParentFolder = resolveParentFolderReferenceFromSingleDocument(uri)
            renderStandaloneFileFallback(uri, fallbackParentFolder)
            Log.d(
                TAG,
                "playSelectedAudio optional explorer restore fallback used uri=$uri fallbackParent=${fallbackParentFolder?.uri}"
            )
            Toast.makeText(this, R.string.file_context_unavailable, Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Log.d(
                TAG,
                "playSelectedAudio optional explorer restore failed for uri=$uri: ${error.message}"
            )
            renderStandaloneFileFallback(uri, resolveParentFolderReferenceFromSingleDocument(uri))
            Toast.makeText(this, R.string.file_context_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun resolveParentFolderFromSingleDocument(fileUri: Uri): DocumentFile? {
        val folder = resolveParentFolderReferenceFromSingleDocument(fileUri) ?: return null
        Log.d(
            TAG,
            "resolveParentFolderFromSingleDocument uri=$fileUri parentUri=${folder.uri} source=single-document"
        )
        return folder
    }

    private fun resolveParentFolderReferenceFromSingleDocument(fileUri: Uri): DocumentFile? {
        val authority = fileUri.authority ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull() ?: return null
        val separatorIndex = docId.lastIndexOf('/')
        if (separatorIndex <= 0) return null
        val parentDocId = docId.substring(0, separatorIndex)
        val parentUri = DocumentsContract.buildDocumentUri(authority, parentDocId)
        val folder = DocumentFile.fromSingleUri(this, parentUri) ?: return null
        if (!folder.exists() || !folder.isDirectory) return null
        Log.d(TAG, "resolveParentFolderReferenceFromSingleDocument uri=$fileUri parentUri=$parentUri source=single-document")
        return folder
    }

    private fun openBranch(folder: DocumentFile) {
        Log.d(TAG, "folder navigation change -> selectedUri=${folder.uri}")
        mainScope.launch { renderFolderContext(folder, currentTrackUri) }
    }

    private suspend fun renderFolderContext(
        selectedFolder: DocumentFile,
        selectedTrackUri: String?
    ) {
        if (!repository.isKnownFolderReference(selectedFolder)) {
            showUsbError()
            return
        }

        selectedLeftFolder = selectedFolder
        selectedRightFolder = selectedFolder
        currentRightTrackScope = selectedFolder
        val selectedFolderUri = selectedFolder.uri.toString()
        Log.d(TAG, "selected folder uri=$selectedFolderUri")

        val rootFolder = resolveActiveNavigationRoot(selectedFolder)
        expandedRightFolderUris += rootFolder.uri.toString()
        expandedRightFolderUris += selectedFolderUri
        val breadcrumb = buildBreadcrumb(selectedFolder, rootFolder.uri)
        currentBreadcrumb = breadcrumb
        pathText.text = breadcrumb
        topSourceText.text = getString(R.string.showing_music_in, breadcrumb)
        usbStatusText.text = getString(R.string.status_usb_connected)

        val tracks = runCatching { repository.collectSupportedAudioRecursively(selectedFolder) }
            .getOrElse {
                Log.d(TAG, "renderFolderContext collectSupportedAudioRecursively failed folder=${selectedFolder.uri}: ${it.message}")
                emptyList()
            }
        leftAdapter.submitList(tracks.map { FileEntryAdapter.EntryItem(documentFile = it) })
        leftAdapter.setHighlightedUri(selectedTrackUri)
        val wasHighlighted = leftAdapter.findPositionByUri(selectedTrackUri) != RecyclerView.NO_POSITION

        renderRightFolderTree(rootFolder)
        Log.d(TAG, "renderFolderContext used=true selectedFolderUri=${selectedFolder.uri}")
        Log.d(TAG, "left-panel track count=${tracks.size} selectedFolderUri=${selectedFolder.uri} highlighted=$wasHighlighted")
        findViewById<TextView>(R.id.leftEmptyText).text = getString(R.string.no_supported_audio)
        findViewById<TextView>(R.id.leftEmptyText).visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
    }

    private suspend fun renderRightFolderTree(rootFolder: DocumentFile) {
        val rootChildren = runCatching { repository.listChildFoldersOnly(rootFolder) }.getOrElse { emptyList() }
        val folderItems = mutableListOf<FileEntryAdapter.EntryItem>()
        rootChildren.forEach { folder ->
            appendFolderNode(folderItems, folder)
        }
        rightAdapter.submitList(folderItems)
        rightAdapter.setSelectedKey(selectedRightFolder?.uri?.toString())
        findViewById<TextView>(R.id.rightEmptyText).text = getString(R.string.no_folders_found)
        findViewById<TextView>(R.id.rightEmptyText).visibility = if (folderItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private suspend fun appendFolderNode(
        target: MutableList<FileEntryAdapter.EntryItem>,
        folder: DocumentFile,
        depth: Int = 0
    ) {
        target += FileEntryAdapter.EntryItem(documentFile = folder, depth = depth)
        if (!expandedRightFolderUris.contains(folder.uri.toString())) return
        val children = runCatching { repository.listChildFoldersOnly(folder) }.getOrElse { emptyList() }
        children.forEach { child -> appendFolderNode(target, child, depth + 1) }
    }

    private fun onRightFolderTapped(folder: DocumentFile) {
        val folderUri = folder.uri.toString()
        val isActiveFolder = selectedRightFolder?.uri == folder.uri
        if (!isActiveFolder) {
            expandedRightFolderUris += folderUri
            openBranch(folder)
            return
        }

        val root = resolveActiveNavigationRoot(folder)
        val parent = resolveParentWithinRoot(folder, root)
        if (parent != null && parent.uri != folder.uri) {
            collapseRightBranch(folder, includeSelf = true)
            openBranch(parent)
        } else {
            collapseRightBranch(folder, includeSelf = false)
            openBranch(folder)
        }
    }

    private fun collapseRightBranch(folder: DocumentFile, includeSelf: Boolean) {
        val collapseDocId = runCatching { DocumentsContract.getDocumentId(folder.uri) }.getOrNull()
        if (collapseDocId == null) {
            if (includeSelf) {
                expandedRightFolderUris.remove(folder.uri.toString())
            }
            return
        }
        val toRemove = expandedRightFolderUris.filter { uriString ->
            val expandedDocId = runCatching {
                DocumentsContract.getDocumentId(Uri.parse(uriString))
            }.getOrNull() ?: return@filter false

            if (includeSelf) {
                expandedDocId == collapseDocId || expandedDocId.startsWith("$collapseDocId/")
            } else {
                expandedDocId.startsWith("$collapseDocId/")
            }
        }
        expandedRightFolderUris.removeAll(toRemove.toSet())
    }

    private fun renderRootOverview(root: DocumentFile) {
        mainScope.launch {
            selectedRightFolder = null
            selectedLeftFolder = null
            expandedRightFolderUris.clear()
            expandedRightFolderUris += root.uri.toString()
            currentBreadcrumb = currentSourceLabel ?: root.name ?: "Music"
            pathText.text = currentBreadcrumb
            topSourceText.text = getString(R.string.showing_music_in, currentBreadcrumb)
            usbStatusText.text = getString(R.string.status_usb_connected)
            leftAdapter.submitList(emptyList())
            leftAdapter.setSelectedKey(null)
            leftAdapter.setHighlightedUri(currentTrackUri)
            findViewById<TextView>(R.id.leftEmptyText).text = getString(R.string.select_folder_to_show_songs)
            findViewById<TextView>(R.id.leftEmptyText).visibility = View.VISIBLE
            renderRightFolderTree(root)
        }
    }

    private fun resolveActiveNavigationRoot(selectedFolder: DocumentFile): DocumentFile {
        val root = navigationTreeUri?.let { DocumentFile.fromTreeUri(this, it) }
        if (root != null && root.exists() && isDescendantOfBranch(selectedFolder, root)) {
            return root
        }
        navigationTreeUri = findBestNavigationTreeUriForFolder(selectedFolder)
        val fallbackRoot = navigationTreeUri?.let { DocumentFile.fromTreeUri(this, it) }
        return if (fallbackRoot != null && fallbackRoot.exists() && isDescendantOfBranch(selectedFolder, fallbackRoot)) {
            fallbackRoot
        } else {
            selectedFolder
        }
    }

    private suspend fun resolveContainingFolderFromPickedFile(fileUri: Uri): DocumentFile? {
        val authority = fileUri.authority
        if (authority.isNullOrBlank()) {
            return null
        }
        val treeCandidates = buildList {
            val selectedRoot = navigationTreeUri
            Log.d(TAG, "resolveContainingFolderFromPickedFile selectedRootUri=$selectedRoot selectedRootIsNull=${selectedRoot == null}")
            if (selectedRoot != null && selectedRoot.authority == authority && DocumentsContract.isTreeUri(selectedRoot)) {
                add(selectedRoot)
            }
            addAll(
                contentResolver.persistedUriPermissions
                    .asSequence()
                    .filter { it.isReadPermission && it.uri.authority == authority && DocumentsContract.isTreeUri(it.uri) }
                    .map { it.uri }
                    .toList()
            )
        }.distinct()
        Log.d(TAG, "resolveContainingFolderFromPickedFile authority=$authority persistedTreeCandidates=${treeCandidates.size}")

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

        val bestTreeUri = treeCandidates
            .filter { treeUri ->
                val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                treeDocId != null && (parentDocId == treeDocId || parentDocId.startsWith("$treeDocId/"))
            }
            .maxByOrNull { treeUri ->
                runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()?.length ?: -1
            }
        Log.d(TAG, "resolveContainingFolderFromPickedFile bestMatchingTreeUri=$bestTreeUri authority=$authority")

        val prioritizedCandidates = buildList {
            if (bestTreeUri != null) add(bestTreeUri)
            addAll(treeCandidates.filterNot { it == bestTreeUri })
        }

        prioritizedCandidates.forEach { treeUri ->
            if (treeUri.authority != authority) return@forEach
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            Log.d(TAG, "resolveContainingFolderFromPickedFile checkingTreeUri=$treeUri treeDocId=$treeDocId authority=${treeUri.authority}")
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            val parentTreeUri = DocumentsContract.buildTreeDocumentUri(authority, parentDocId)
            val folder = sequenceOf(
                DocumentFile.fromTreeUri(this, parentTreeUri),
                DocumentFile.fromTreeUri(this, parentUri),
                DocumentFile.fromSingleUri(this, parentUri)
            ).firstOrNull { it != null && it.exists() && it.isDirectory } ?: return@forEach
            if (!folder.exists() || !folder.isDirectory) return@forEach
            val canListChildren = canEnumerateFolderContext(folder)
            Log.d(
                TAG,
                "resolveContainingFolderFromPickedFile tree candidate uri=$parentUri enumerable=$canListChildren source=tree-based"
            )
            Log.d(
                TAG,
                "resolveContainingFolderFromPickedFile success for uri=$fileUri parentUri=${folder.uri} source=tree-based"
            )
            return folder
        }
        return null
    }


    private suspend fun renderStandaloneFileFallback(fileUri: Uri, fallbackFolder: DocumentFile?) {
        val treeReconstructedFolder = resolveContainingFolderFromPickedFile(fileUri)
        if (treeReconstructedFolder != null && treeReconstructedFolder.exists() && treeReconstructedFolder.isDirectory) {
            alignExplorerToFolder(treeReconstructedFolder)
            return
        }

        if (fallbackFolder != null && fallbackFolder.exists() && fallbackFolder.isDirectory) {
            val promoted = runCatching {
                alignExplorerToFolder(fallbackFolder)
                true
            }.getOrElse {
                Log.d(
                    TAG,
                    "renderStandaloneFileFallback folder promotion failed folderUri=${fallbackFolder.uri}: ${it.message}"
                )
                false
            }
            if (promoted) return
        }

        val selectedFile = DocumentFile.fromSingleUri(this, fileUri)
            ?.takeIf { it.exists() && !it.isDirectory }
        val leftItems = selectedFile?.let { listOf(FileEntryAdapter.EntryItem(documentFile = it)) } ?: emptyList()
        leftAdapter.submitList(leftItems)
        leftAdapter.setSelectedKey(null)
        leftAdapter.setHighlightedUri(currentTrackUri)
        findViewById<TextView>(R.id.leftEmptyText).text = getString(R.string.empty_right_folder_tracks_hint)
        findViewById<TextView>(R.id.leftEmptyText).visibility = if (leftItems.isEmpty()) View.VISIBLE else View.GONE

        Log.d(
            TAG,
            "renderStandaloneFileFallback used=true playbackOnly=true rightExplorerPreserved=true fileUri=$fileUri"
        )
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
        val canEnumerate = canEnumerateFolderContext(folder)
        Log.d(TAG, "resolved containing folder uri=${folder.uri} enumerable=$canEnumerate")
        navigationTreeUri = findBestNavigationTreeUriForFolder(folder)
        Log.d(TAG, "alignExplorerToFolder used=true folderUri=${folder.uri} navigationTreeUri=$navigationTreeUri")
        renderFolderContext(folder, currentTrackUri)
    }

    private fun syncInitialFolderContext() {
        currentSourceId = null
        currentSourceLabel = null
        usbStatusText.text = getString(R.string.status_usb_ready)
        pathText.text = getString(R.string.sources)
        topSourceText.text = getString(R.string.top_source_roots)
        selectedRightFolder = null
        selectedLeftFolder = null
        navigationTreeUri = null
        expandedRightFolderUris.clear()
        leftAdapter.submitList(emptyList())
        leftAdapter.setSelectedKey(null)
        leftAdapter.setHighlightedUri(currentTrackUri)
        renderStartupSourceList()
        findViewById<TextView>(R.id.leftEmptyText).text = getString(R.string.select_source_to_show_songs)
        findViewById<TextView>(R.id.leftEmptyText).visibility = View.VISIBLE
        findViewById<TextView>(R.id.rightEmptyText).visibility = View.GONE
        Log.d(TAG, "syncInitialFolderContext startup source list rendered")
    }

    private fun resetToStartupSourceList() {
        syncInitialFolderContext()
    }

    private fun renderStartupSourceList() {
        val startupItems = mutableListOf(
            FileEntryAdapter.EntryItem(
                customId = SOURCE_MUSIC,
                customName = getString(R.string.startup_source_music),
                customIcon = "📁",
                isCustomFolder = true
            )
        )
        val removableCount = detectConnectedRemovableCount().coerceIn(0, MAX_USB_SOURCES)
        for (index in 1..removableCount) {
            startupItems += FileEntryAdapter.EntryItem(
                customId = usbSourceId(index),
                customName = getString(R.string.startup_source_usb, index),
                customIcon = "💾",
                isCustomFolder = true
            )
        }
        rightAdapter.submitList(startupItems)
        rightAdapter.setSelectedKey(null)
    }

    private fun detectConnectedRemovableCount(): Int {
        val storageManager = getSystemService(StorageManager::class.java) ?: return 0
        return storageManager.storageVolumes.count { volume ->
            volume.isRemovable &&
                !volume.isPrimary &&
                (volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY)
        }
    }

    private fun onStartupSourceTapped(sourceId: String) {
        pendingSourceIdForPicker = sourceId
        currentSourceId = sourceId
        currentSourceLabel = sourceLabelForId(sourceId)
        rightAdapter.setSelectedKey(sourceId)
        val savedTreeUri = prefs.getString(sourcePrefKey(sourceId), null)?.let(Uri::parse)
        if (savedTreeUri != null && isValidTreeUri(savedTreeUri)) {
            Log.d(TAG, "Opening saved source tree for sourceId=$sourceId uri=$savedTreeUri")
            openSavedSourceTree(sourceId, savedTreeUri)
            return
        }
        if (savedTreeUri != null) {
            prefs.edit { remove(sourcePrefKey(sourceId)) }
        }
        Log.d(TAG, "No saved source tree for sourceId=$sourceId. Launching picker.")
        chooseFolderLauncher.launch(null)
    }

    private fun sourcePrefKey(sourceId: String): String = "${KEY_SOURCE_TREE_PREFIX}$sourceId"

    private fun usbSourceId(index: Int): String = "usb_$index"

    private fun sourceLabelForId(sourceId: String): String {
        return if (sourceId == SOURCE_MUSIC) {
            getString(R.string.startup_source_music)
        } else {
            sourceId.removePrefix("usb_").toIntOrNull()?.let { index ->
                getString(R.string.startup_source_usb, index)
            } ?: sourceId
        }
    }

    private fun isValidTreeUri(uri: Uri): Boolean {
        val root = DocumentFile.fromTreeUri(this, uri) ?: return false
        return root.exists() && root.isDirectory
    }

    private fun navigateToParentFolder() {
        val current = selectedLeftFolder ?: return
        val root = resolveActiveNavigationRoot(current)
        val parent = resolveParentWithinRoot(current, root) ?: return
        Log.d(TAG, "left folder navigation up: from=${current.uri} to=${parent.uri}")
        openBranch(parent)
    }

    private fun resolveParentWithinRoot(folder: DocumentFile, root: DocumentFile): DocumentFile? {
        if (folder.uri == root.uri) {
            val directParent = resolveParentFromDocumentFile(folder)
            if (directParent != null) return directParent
        }
        val currentDocId = runCatching { DocumentsContract.getDocumentId(folder.uri) }.getOrNull() ?: return null
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(root.uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getDocumentId(root.uri) }.getOrNull()
        if (rootDocId != null && currentDocId == rootDocId) return null
        if (rootDocId != null && !currentDocId.startsWith("$rootDocId/") && currentDocId != rootDocId) {
            return resolveParentFromDocumentId(folder, currentDocId)
        }
        val separatorIndex = currentDocId.lastIndexOf('/')
        val parentDocId = if (separatorIndex > 0) {
            currentDocId.substring(0, separatorIndex)
        } else {
            rootDocId ?: return null
        }
        val treeParent = runCatching {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(root.uri, parentDocId)
            DocumentFile.fromTreeUri(this, parentUri)
        }.getOrNull()
        if (treeParent != null && treeParent.exists() && treeParent.isDirectory) {
            return treeParent
        }
        return resolveParentFromDocumentId(folder, currentDocId)
    }

    private fun resolveParentFromDocumentFile(folder: DocumentFile): DocumentFile? {
        val currentDocId = runCatching { DocumentsContract.getDocumentId(folder.uri) }.getOrNull() ?: return null
        return resolveParentFromDocumentId(folder, currentDocId)
    }

    private fun resolveParentFromDocumentId(folder: DocumentFile, currentDocId: String): DocumentFile? {
        val separatorIndex = currentDocId.lastIndexOf('/')
        if (separatorIndex <= 0) return null
        val authority = folder.uri.authority ?: return null
        val parentDocId = currentDocId.substring(0, separatorIndex)
        val parentUri = DocumentsContract.buildDocumentUri(authority, parentDocId)
        val parent = DocumentFile.fromSingleUri(this, parentUri) ?: return null
        return if (parent.exists() && parent.isDirectory) parent else null
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
        leftAdapter.setHighlightedUri(currentTrackUri)
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

    private fun findBestNavigationTreeUriForFolder(folder: DocumentFile): Uri? {
        val folderAuthority = folder.uri.authority
        val folderDocId = runCatching { DocumentsContract.getDocumentId(folder.uri) }.getOrNull() ?: return null
        val best = contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) && it.uri.authority == folderAuthority }
            .mapNotNull { perm ->
                val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(perm.uri) }.getOrNull()
                    ?: return@mapNotNull null
                if (folderDocId == treeDocId || folderDocId.startsWith("$treeDocId/")) {
                    treeDocId.length to perm.uri
                } else {
                    null
                }
            }
            .maxByOrNull { it.first }
            ?.second
        Log.d(TAG, "findBestNavigationTreeUriForFolder folderUri=${folder.uri} authority=$folderAuthority matchedTreeUri=$best")
        return best
    }

    private suspend fun canEnumerateFolderContext(folder: DocumentFile): Boolean {
        val enumerable = repository.canEnumerateFolderContext(folder)
        Log.d(TAG, "canEnumerateFolderContext folderUri=${folder.uri} enumerable=$enumerable")
        return enumerable
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
        val target = leftAdapter.findPositionByUri(currentTrackUri)
        if (target != RecyclerView.NO_POSITION) {
            leftRecycler.smoothScrollToPosition(target)
        }
    }

    private fun showTrackInfoDialog() {
        val trackFile = leftAdapter.getItemByUri(currentTrackUri)?.name
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
        private const val KEY_LAST_TREE_URI = "last_tree_uri"
        private const val KEY_SOURCE_TREE_PREFIX = "source_tree_uri_"
        private const val SOURCE_MUSIC = "music"
        private const val MAX_USB_SOURCES = 5
        private const val CLOCK_REFRESH_MS = 30_000L
        private const val PLAYER_PROGRESS_POLL_MS = 1_000L
    }
}
