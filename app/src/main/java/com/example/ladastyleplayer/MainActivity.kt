package com.example.ladastyleplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.ImageView
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

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val repository = FileRepository()
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var leftAdapter: FileEntryAdapter
    private lateinit var rightAdapter: FileEntryAdapter

    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var coverImage: ImageView

    private var currentFolder: DocumentFile? = null

    private val chooseFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            prefs.edit { putString(KEY_TREE_URI, uri.toString()) }
            openTree(uri)
        }

    private val trackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != PlayerService.ACTION_TRACK_CHANGED) return
            titleText.text = intent.getStringExtra(PlayerService.EXTRA_TITLE) ?: getString(R.string.no_track)
            val b64 = intent.getStringExtra(PlayerService.EXTRA_COVER_B64)
            if (!b64.isNullOrBlank()) {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                coverImage.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } else {
                coverImage.setImageResource(android.R.drawable.ic_menu_report_image)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        titleText = findViewById(R.id.titleText)
        coverImage = findViewById(R.id.coverImage)

        setupRecyclerViews()
        setupButtons()

        val persistedTree = prefs.getString(KEY_TREE_URI, null)
        if (persistedTree != null) {
            openTree(Uri.parse(persistedTree), resume = true)
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiverCompat(trackReceiver, IntentFilter(PlayerService.ACTION_TRACK_CHANGED))
    }

    override fun onStop() {
        unregisterReceiver(trackReceiver)
        super.onStop()
    }

    private fun setupRecyclerViews() {
        leftAdapter = FileEntryAdapter(
            onFolderClick = { folder -> openFolder(folder) },
            onFileClick = { file -> playSingle(file) },
            onPlayFolder = { folder -> playAll(folder, false) }
        )
        rightAdapter = FileEntryAdapter(
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
            chooseFolderLauncher.launch(null)
        }
        findViewById<Button>(R.id.playPauseButton).setOnClickListener {
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
        findViewById<Button>(R.id.playAllButton).setOnClickListener {
            currentFolder?.let { playAll(it, true) }
        }
    }

    private fun openTree(uri: Uri, resume: Boolean = false) {
        try {
            val root = DocumentFile.fromTreeUri(this, uri)
            if (root == null || !root.exists()) {
                showUsbError()
                return
            }
            statusText.text = getString(R.string.status_usb_ready)
            currentFolder = root
            openFolder(root)
            if (resume) playAll(root, true)
        } catch (e: Exception) {
            showUsbError()
        }
    }

    private fun openFolder(folder: DocumentFile) {
        currentFolder = folder
        mainScope.launch {
            runCatching { repository.listChildren(folder) }
                .onSuccess { items ->
                    val folders = items.filter { it.isDirectory }
                    val files = items.filter { it.isFile }
                    leftAdapter.submitList(folders)
                    rightAdapter.submitList(if (folders.isNotEmpty()) folders else files)
                }
                .onFailure {
                    showUsbError()
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

    private fun showUsbError() {
        statusText.text = getString(R.string.status_no_usb)
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
    }
}
