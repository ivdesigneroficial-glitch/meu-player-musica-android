package com.ivan.playermusica

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.ivan.playermusica.databinding.ActivityMainBinding
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SongAdapter

    private var allSongs: List<Song> = emptyList()   // everything on device
    private var visible: List<Song> = emptyList()     // filtered/queue
    private var currentIndex = -1

    private val player = MediaPlayer()
    private var prepared = false

    private var shuffle = false
    private var repeatMode = 0 // 0 = off, 1 = all, 2 = one
    private val history = ArrayDeque<Int>()

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadSongs()
            registerObserver()
        } else {
            binding.emptyState.text =
                "Preciso da permissão de acesso às músicas para mostrar suas faixas.\n\nAbra as configurações do app e permita o acesso a Música/Áudio."
            binding.emptyState.visibility = android.view.View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        volumeControlStream = AudioManager.STREAM_MUSIC

        adapter = SongAdapter(emptyList()) { pos -> playAt(pos) }
        binding.songList.layoutManager = LinearLayoutManager(this)
        binding.songList.adapter = adapter

        setupPlayer()
        setupControls()
        setupSearch()

        requestPermissionAndLoad()
    }

    private fun permissionName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun requestPermissionAndLoad() {
        val perm = permissionName()
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadSongs()
            registerObserver()
        } else {
            permLauncher.launch(perm)
        }
    }

    private fun loadSongs() {
        val songs = mutableListOf<Song>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                var title = cursor.getString(titleCol) ?: ""
                if (title.isBlank()) {
                    title = (cursor.getString(nameCol) ?: "Sem título").substringBeforeLast('.')
                }
                val artist = cursor.getString(artistCol) ?: ""
                val album = cursor.getString(albumCol) ?: ""
                val albumId = cursor.getLong(albumIdCol)
                val duration = cursor.getLong(durCol)
                val uri = ContentUris.withAppendedId(collection, id)
                songs.add(Song(id, title, artist, album, albumId, duration, uri))
            }
        }

        allSongs = songs
        applyFilter(binding.searchInput.text?.toString() ?: "")
        binding.countLabel.text = if (allSongs.isEmpty()) "" else "${allSongs.size} músicas"
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        visible = if (q.isEmpty()) allSongs
        else allSongs.filter {
            it.title.lowercase().contains(q) ||
            it.artist.lowercase().contains(q) ||
            it.album.lowercase().contains(q)
        }
        adapter.update(visible)
        binding.emptyState.visibility =
            if (allSongs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.songList.visibility =
            if (allSongs.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun registerObserver() {
        if (observer != null) return
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // A new song was downloaded/added/removed — refresh automatically
                loadSongs()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer!!
        )
    }

    private fun setupPlayer() {
        player.setOnCompletionListener {
            nextTrack(auto = true)
        }
        player.setOnPreparedListener {
            prepared = true
            it.start()
            binding.seek.max = it.duration
            binding.durTime.text = formatTime(it.duration.toLong())
            updatePlayIcon()
            startSeekUpdater()
        }
    }

    private fun setupControls() {
        binding.btnPlaypause.setOnClickListener {
            if (currentIndex == -1 && visible.isNotEmpty()) { playAt(0); return@setOnClickListener }
            if (player.isPlaying) { player.pause() } else { if (prepared) player.start() }
            updatePlayIcon()
        }
        binding.btnNext.setOnClickListener { nextTrack(auto = false) }
        binding.btnPrev.setOnClickListener { prevTrack() }
        binding.btnShuffle.setOnClickListener {
            shuffle = !shuffle
            history.clear()
            binding.btnShuffle.setColorFilter(
                if (shuffle) 0xFFF6C915.toInt() else 0xFF9797A8.toInt()
            )
        }
        binding.btnRepeat.setOnClickListener {
            repeatMode = (repeatMode + 1) % 3
            binding.btnRepeat.setColorFilter(
                if (repeatMode != 0) 0xFFF6C915.toInt() else 0xFF9797A8.toInt()
            )
        }
        binding.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.curTime.text = formatTime(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (prepared) player.seekTo(sb?.progress ?: 0)
            }
        })
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                applyFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun playAt(index: Int) {
        if (index < 0 || index >= visible.size) return
        currentIndex = index
        history.clear()
        loadAndPlay(visible[index])
    }

    private fun loadAndPlay(song: Song) {
        try {
            prepared = false
            player.reset()
            player.setDataSource(this, song.uri)
            player.prepareAsync()
            binding.playerBar.visibility = android.view.View.VISIBLE
            binding.npTitle.text = song.title
            binding.npArtist.text =
                if (song.artist.isBlank() || song.artist == "<unknown>") "Artista desconhecido" else song.artist
            adapter.setPlaying(song.id)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun nextTrack(auto: Boolean) {
        if (visible.isEmpty()) return
        if (repeatMode == 2 && auto) {
            player.seekTo(0); player.start(); return
        }
        when {
            shuffle && visible.size > 1 -> {
                history.addLast(currentIndex)
                var r: Int
                do { r = Random.nextInt(visible.size) } while (r == currentIndex)
                currentIndex = r
            }
            currentIndex < visible.size - 1 -> currentIndex++
            repeatMode == 1 -> currentIndex = 0
            else -> { player.pause(); updatePlayIcon(); return }
        }
        loadAndPlay(visible[currentIndex])
    }

    private fun prevTrack() {
        if (visible.isEmpty()) return
        if (prepared && player.currentPosition > 3000) { player.seekTo(0); return }
        when {
            shuffle && history.isNotEmpty() -> currentIndex = history.removeLast()
            currentIndex > 0 -> currentIndex--
            repeatMode == 1 -> currentIndex = visible.size - 1
            else -> { player.seekTo(0); return }
        }
        loadAndPlay(visible[currentIndex])
    }

    private fun updatePlayIcon() {
        binding.btnPlaypause.setImageResource(
            if (player.isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private val seekRunnable = object : Runnable {
        override fun run() {
            if (prepared && player.isPlaying) {
                binding.seek.progress = player.currentPosition
                binding.curTime.text = formatTime(player.currentPosition.toLong())
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun startSeekUpdater() {
        handler.removeCallbacks(seekRunnable)
        handler.post(seekRunnable)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    override fun onResume() {
        super.onResume()
        // Re-check music every time the app comes to foreground (catches new downloads)
        val perm = permissionName()
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadSongs()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(seekRunnable)
        observer?.let { contentResolver.unregisterContentObserver(it) }
        player.release()
    }
}
