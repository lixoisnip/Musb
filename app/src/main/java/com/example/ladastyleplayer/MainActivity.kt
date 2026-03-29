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
    private var selectedLeftBranchUri: String? = null
    private var rootTreeUri: Uri? = null
    private var selectedLeftBranchFolder: DocumentFile? = null
    private var currentRightBranchContext: DocumentFile? = null
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

            if (!persistUriPermission(uri)) {
                Toast.makeText(this, R.string.uri_permission_failed, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            prefs.edit { putString(KEY_TREE_URI, uri.toString()) }
            Log.d(TAG, "tree selection persisted uri=$uri")
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
            onFolderClick = { folder ->
                selectLeftBranch(folder.uri.toString())
                openBranch(folder)
            },
            onFileClick = { file -> playSingle(file) },
            onPlayFolder = { folder -> playAll(folder, false) },
            onUpClick = null
        )
        rightAdapter = FileEntryAdapter(
            showPlayFolderButton = false,
            onFolderClick = { folder -> narrowRightBranch(folder) },
            onFileClick = { file -> playSingle(file) },
            onPlayFolder = { folder -> playAll(folder, false) },
            onUpClick = { navigateRightUp() }
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

    private fun openTree(uri: Uri, resume: Boolean = false) {
        Log.d(TAG, "openTree(uri=$uri, resume=$resume)")
        try {
            val root = DocumentFile.fromTreeUri(this, uri)
            if (root == null || !root.exists()) {
                showUsbError()
                return
            }
            rootTreeUri = uri
            pathText.text = "USB • Music"
            topSourceText.text = getString(R.string.top_source_placeholder)
            usbStatusText.text = getString(R.string.status_usb_connected)
            selectedLeftBranchUri = null
            selectedLeftBranchFolder = null
            currentRightBranchContext = null
            currentBreadcrumb = "Music"
            Log.d(TAG, "openTree success rootUri=${root.uri} resume=$resume")
            refreshMusicExplorer(root)
            if (resume) {
                mainScope.launch {
                    val defaultBranch = selectedLeftBranchFolder ?: root.takeIf { repository.hasMusicInBranch(it) }
                    if (defaultBranch != null) playAll(defaultBranch, true)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "openTree failed uri=$uri message=${e.message}")
            showUsbError()
        }
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
        val hasTreeContext = rootTreeUri != null
        if (!hasTreeContext) {
            Log.d(
                TAG,
                "playSelectedAudio fallback: no folder tree context available; skipping parent folder reconstruction"
            )
            return
        }

        try {
            val parentFolder = resolveParentFolderFromPickedFile(uri)
            if (parentFolder != null) {
                Log.d(
                    TAG,
                    "playSelectedAudio optional explorer restore success parentFolderUri=${parentFolder.uri}"
                )
                alignExplorerToFolder(parentFolder)
            } else {
                Log.d(
                    TAG,
                    "playSelectedAudio optional explorer restore skipped: parent folder unresolved for uri=$uri"
                )
            }
        } catch (error: Exception) {
            Log.d(
                TAG,
                "playSelectedAudio optional explorer restore failed for uri=$uri: ${error.message}"
            )
        }
    }

    private fun refreshMusicExplorer(root: DocumentFile) {
        mainScope.launch {
            runCatching {
                repository.collectMusicRelevantFolders(root)
            }.onSuccess { branches ->
                Log.d(TAG, "left-branch population root=${root.uri} branches=${branches.size}")
                val initialBranch = branches.firstOrNull()
                selectedLeftBranchFolder = initialBranch
                currentRightBranchContext = initialBranch

                leftAdapter.submitList(branches.map { FileEntryAdapter.EntryItem(documentFile = it) })
                selectLeftBranch(initialBranch?.uri?.toString())

                findViewById<TextView>(R.id.leftEmptyText).text = getString(R.string.no_subfolders_found)
                findViewById<TextView>(R.id.leftEmptyText).visibility =
                    if (branches.isEmpty()) View.VISIBLE else View.GONE

                if (initialBranch != null) {
                    renderRightBranchContent(initialBranch)
                } else {
                    renderRightBranchContent(root)
                }
            }.onFailure {
                Log.d(TAG, "left-branch population failed root=${root.uri} message=${it.message}")
                showUsbError()
                findViewById<TextView>(R.id.leftEmptyText).visibility = View.VISIBLE
                findViewById<TextView>(R.id.rightEmptyText).visibility = View.VISIBLE
            }
        }
    }

    private fun openBranch(folder: DocumentFile) {
        Log.d(TAG, "left branch selected folder=${folder.uri}")
        selectedLeftBranchFolder = folder
        currentRightBranchContext = folder
        renderRightBranchContent(folder)
    }

    private fun narrowRightBranch(folder: DocumentFile) {
        Log.d(TAG, "right-branch narrowing target=${folder.uri}")
        currentRightBranchContext = folder
        renderRightBranchContent(folder)
    }

    private fun renderRightBranchContent(folder: DocumentFile) {
        val breadcrumb = buildBreadcrumb(folder)
        currentBreadcrumb = breadcrumb
        pathText.text = "USB • Music • $breadcrumb"
        topSourceText.text = "Showing music in $breadcrumb"

        mainScope.launch {
            runCatching {
                repository.collectBranchContent(folder)
            }.onSuccess { content ->
                Log.d(
                    TAG,
                    "right-branch population folder=${folder.uri} breadcrumb=$currentBreadcrumb subfolders=${content.subfolders.size} tracks=${content.audioFiles.size}"
                )
                val rightItems = buildList {
                    val canGoUp = selectedLeftBranchFolder != null && folder.uri != selectedLeftBranchFolder?.uri
                    if (canGoUp) add(FileEntryAdapter.EntryItem(isUpItem = true))
                    addAll(content.subfolders.map { FileEntryAdapter.EntryItem(documentFile = it) })
                    addAll(content.audioFiles.map { FileEntryAdapter.EntryItem(documentFile = it) })
                }
                rightAdapter.submitList(rightItems)
                rightAdapter.setHighlightedUri(currentTrackUri)

                findViewById<TextView>(R.id.rightEmptyText).text = getString(R.string.no_supported_audio)
                findViewById<TextView>(R.id.rightEmptyText).visibility =
                    if (content.subfolders.isEmpty() && content.audioFiles.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure {
                Log.d(TAG, "right-branch population failed folder=${folder.uri} message=${it.message}")
                showUsbError()
                findViewById<TextView>(R.id.rightEmptyText).visibility = View.VISIBLE
            }
        }
    }

    private suspend fun resolveParentFolderFromPickedFile(fileUri: Uri): DocumentFile? {
        val treeUri = rootTreeUri ?: return null
        val authority = fileUri.authority
        if (authority.isNullOrBlank() || authority != treeUri.authority) {
            Log.d(TAG, "resolveParentFolderFromPickedFile skipped: authority mismatch tree=${treeUri.authority} file=$authority uri=$fileUri")
            return null
        }

        val docId = runCatching { DocumentsContract.getDocumentId(fileUri) }
            .onFailure { Log.d(TAG, "resolveParentFolderFromPickedFile no documentId for uri=$fileUri: ${it.message}") }
            .getOrNull()
            ?: return null
        val separatorIndex = docId.lastIndexOf('/')
        if (separatorIndex <= 0) {
            Log.d(TAG, "resolveParentFolderFromPickedFile no parent docId for docId=$docId")
            return null
        }
        val parentDocId = docId.substring(0, separatorIndex)
        Log.d(TAG, "resolveParentFolderFromPickedFile docId=$docId parentDocId=$parentDocId")

        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val folder = DocumentFile.fromTreeUri(this, parentUri) ?: return null
        if (!folder.exists() || !folder.isDirectory) {
            Log.d(
                TAG,
                "resolveParentFolderFromPickedFile rejected tree candidate uri=$parentUri exists=${folder.exists()} isDirectory=${folder.isDirectory}"
            )
            return null
        }

        val canListChildren = try {
            repository.listChildren(folder)
            true
        } catch (error: Exception) {
            Log.d(
                TAG,
                "resolveParentFolderFromPickedFile failed listing candidate uri=$parentUri: ${error.message}"
            )
            false
        }
        Log.d(
            TAG,
            "resolveParentFolderFromPickedFile tree candidate uri=$parentUri canListChildren=$canListChildren"
        )
        if (!canListChildren) return null

        Log.d(
            TAG,
            "resolveParentFolderFromPickedFile success for uri=$fileUri parentUri=${folder.uri}"
        )
        return folder
    }

    private fun navigateRightUp() {
        val folder = currentRightBranchContext ?: return
        val branch = selectedLeftBranchFolder ?: return
        if (folder.uri == branch.uri) return
        val parentFolder = getParentFolder(folder) ?: return
        if (parentFolder.uri == branch.uri || isDescendantOfBranch(parentFolder, branch)) {
            currentRightBranchContext = parentFolder
            renderRightBranchContent(parentFolder)
        }
    }

    private fun getParentFolder(folder: DocumentFile): DocumentFile? {
        val treeUri = rootTreeUri ?: return null
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val currentDocId = runCatching { DocumentsContract.getDocumentId(folder.uri) }.getOrNull() ?: return null
        if (currentDocId == rootDocId) return null

        val separatorIndex = currentDocId.lastIndexOf('/')
        val parentDocId = if (separatorIndex >= 0) currentDocId.substring(0, separatorIndex) else rootDocId
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        return DocumentFile.fromTreeUri(this, parentUri)?.takeIf { it.exists() }
    }

    private fun isDescendantOfBranch(candidate: DocumentFile, branch: DocumentFile): Boolean {
        val branchDocId = runCatching { DocumentsContract.getDocumentId(branch.uri) }.getOrNull() ?: return false
        val candidateDocId = runCatching { DocumentsContract.getDocumentId(candidate.uri) }.getOrNull() ?: return false
        return candidateDocId == branchDocId || candidateDocId.startsWith("$branchDocId/")
    }

    private fun buildBreadcrumb(folder: DocumentFile): String {
        val treeUri = rootTreeUri ?: return folder.name ?: "Music"
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return folder.name ?: "Music"
        val currentDocId = runCatching { DocumentsContract.getDocumentId(folder.uri) }.getOrNull()
            ?: return folder.name ?: "Music"
        if (currentDocId == rootDocId) return folder.name ?: "Music"
        val relative = currentDocId.removePrefix("$rootDocId/")
        return relative.ifBlank { folder.name ?: "Music" }.replace("/", " • ")
    }

    private suspend fun alignExplorerToFolder(folder: DocumentFile) {
        val root = DocumentFile.fromTreeUri(this, rootTreeUri ?: return) ?: return
        val branches = runCatching { repository.collectMusicRelevantFolders(root) }.getOrNull() ?: return
        val matchingBranch = branches.firstOrNull { isDescendantOfBranch(folder, it) }
        if (matchingBranch != null) {
            Log.d(
                TAG,
                "single-file flow aligned branch=${matchingBranch.uri} rightContext=${folder.uri}"
            )
            leftAdapter.submitList(branches.map { FileEntryAdapter.EntryItem(documentFile = it) })
            selectedLeftBranchFolder = matchingBranch
            currentRightBranchContext = folder
            selectLeftBranch(matchingBranch.uri.toString())
            renderRightBranchContent(folder)
            findViewById<TextView>(R.id.leftEmptyText).visibility = if (branches.isEmpty()) View.VISIBLE else View.GONE
        } else {
            Log.d(TAG, "single-file flow could not align to branch for folder=${folder.uri}, refreshing")
            refreshMusicExplorer(root)
        }
    }

    private fun selectLeftBranch(uri: String?) {
        selectedLeftBranchUri = uri
        leftAdapter.setSelectedUri(uri)
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
        private const val KEY_TREE_URI = "tree_uri"
        private const val CLOCK_REFRESH_MS = 30_000L
        private const val PLAYER_PROGRESS_POLL_MS = 1_000L
    }
}
