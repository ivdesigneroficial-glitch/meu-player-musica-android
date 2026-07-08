package com.ivan.playermusica

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.ivan.playermusica.databinding.ActivityMainBinding
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RowAdapter
    private lateinit var playlists: PlaylistStore

    private var allSongs: List<Song> = emptyList()

    private var tab = 0
    private var drillKey: String? = null

    private var queue: List<Song> = emptyList()
    private var queueIndex = -1
    private var queueOnPlayList: List<Song> = emptyList()

    private val player = MediaPlayer()
    private var prepared = false

    private var shuffle = false
    private var repeatMode = 0 // 0 off, 1 all, 2 one
    private val history = ArrayDeque<Int>()

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null

    private val artBase: Uri = Uri.parse("content://media/external/audio/albumart")

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { loadSongs(); registerObserver() }
        else {
            binding.emptyState.text =
                "Preciso da permissão de acesso às músicas.\n\nAbra as configurações do app e permita o acesso a Música/Áudio."
            binding.emptyState.visibility = android.view.View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        volumeControlStream = AudioManager.STREAM_MUSIC

        playlists = PlaylistStore(this)

        adapter = RowAdapter(emptyList(), ::onRowClick, ::onRowLongClick)
        binding.songList.layoutManager = LinearLayoutManager(this)
        binding.songList.adapter = adapter

        setupPlayer()
        setupControls()
        setupSearch()
        setupTabs()

        binding.btnBack.setOnClickListener { goUp() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.nowPlaying.visibility == android.view.View.VISIBLE -> closeNowPlaying()
                    drillKey != null -> goUp()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        requestPermissionAndLoad()
    }

    // ---------------- Permission + loading ----------------
    private fun permissionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun requestPermissionAndLoad() {
        if (ContextCompat.checkSelfPermission(this, permissionName()) == PackageManager.PERMISSION_GRANTED) {
            loadSongs(); registerObserver()
        } else permLauncher.launch(permissionName())
    }

    private fun loadSongs() {
        val songs = mutableListOf<Song>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val hasBucket = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA
        )
        if (hasBucket) projection.add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        contentResolver.query(collection, projection.toTypedArray(), selection, null, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val bucketCol = c.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                var title = c.getString(titleCol) ?: ""
                if (title.isBlank()) title = (c.getString(nameCol) ?: "Sem título").substringBeforeLast('.')
                val artist = c.getString(artistCol) ?: ""
                val album = c.getString(albumCol) ?: ""
                val albumId = c.getLong(albumIdCol)
                val duration = c.getLong(durCol)
                var folder = if (bucketCol != -1) c.getString(bucketCol) else null
                if (folder.isNullOrBlank() && dataCol != -1) {
                    val path = c.getString(dataCol)
                    if (!path.isNullOrBlank()) {
                        val parent = path.substringBeforeLast('/', "")
                        folder = parent.substringAfterLast('/', "").ifBlank { "Outros" }
                    }
                }
                if (folder.isNullOrBlank()) folder = "Outros"
                val uri = ContentUris.withAppendedId(collection, id)
                songs.add(Song(id, title, artist, album, albumId, duration, folder, uri))
            }
        }
        allSongs = songs
        refresh()
    }

    private fun registerObserver() {
        if (observer != null) return
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { loadSongs() }
        }
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer!!
        )
    }

    // ---------------- Navigation / rendering ----------------
    private fun setupTabs() {
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position; drillKey = null; binding.searchInput.setText(""); refresh()
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) { drillKey = null; refresh() }
        })
    }

    private fun goUp() { if (drillKey != null) { drillKey = null; refresh() } }

    private fun query(): String = binding.searchInput.text?.toString()?.trim()?.lowercase() ?: ""

    private fun matchesSong(s: Song, q: String) =
        s.title.lowercase().contains(q) || s.artist.lowercase().contains(q) || s.album.lowercase().contains(q)

    private fun refresh() {
        val q = query()
        val rows = mutableListOf<Row>()
        val playingId = if (queueIndex in queue.indices) queue[queueIndex].id else -1L

        val showingSongs: Boolean
        when {
            tab == 0 -> {
                showingSongs = true
                val songs = if (q.isEmpty()) allSongs else allSongs.filter { matchesSong(it, q) }
                queueOnPlayList = songs
                rows += songs.map { Row.SongRow(it) }
            }
            drillKey == null -> { showingSongs = false; rows += buildGroups(q) }
            else -> {
                showingSongs = true
                val songs = songsForDrill()
                val filtered = if (q.isEmpty()) songs else songs.filter { matchesSong(it, q) }
                queueOnPlayList = filtered
                rows += filtered.map { Row.SongRow(it) }
            }
        }

        adapter.update(rows)
        adapter.setPlaying(playingId)

        val empty = rows.isEmpty()
        binding.emptyState.visibility = if (empty) android.view.View.VISIBLE else android.view.View.GONE
        binding.songList.visibility = if (empty) android.view.View.GONE else android.view.View.VISIBLE
        if (empty) {
            binding.emptyState.text = when {
                allSongs.isEmpty() -> "Nenhuma música no aparelho ainda.\n\nBaixe músicas e elas aparecem aqui automaticamente."
                tab == 4 -> "Nenhuma playlist ainda.\n\nToque em \"Nova playlist\" para criar."
                else -> "Nada aqui."
            }
        }

        binding.btnBack.visibility = if (drillKey != null) android.view.View.VISIBLE else android.view.View.GONE
        binding.headerTitle.text = drillKey?.removePrefix("pl:") ?: "Meu Player"
        binding.countLabel.text = if (showingSongs) "${rows.size}" else ""
    }

    private fun buildGroups(q: String): List<Row> {
        val rows = mutableListOf<Row>()
        when (tab) {
            1 -> allSongs.groupBy { if (it.album.isBlank()) "Álbum desconhecido" else it.album }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (name, list) ->
                    if (q.isEmpty() || name.lowercase().contains(q))
                        rows += Row.GroupRow(name, name, "${list.size} músicas", R.drawable.ic_album)
                }
            2 -> allSongs.groupBy { if (it.artist.isBlank() || it.artist == "<unknown>") "Artista desconhecido" else it.artist }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (name, list) ->
                    if (q.isEmpty() || name.lowercase().contains(q))
                        rows += Row.GroupRow(name, name, "${list.size} músicas", R.drawable.ic_artist)
                }
            3 -> allSongs.groupBy { it.folder }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (name, list) ->
                    if (q.isEmpty() || name.lowercase().contains(q))
                        rows += Row.GroupRow(name, name, "${list.size} músicas", R.drawable.ic_folder)
                }
            4 -> {
                rows += Row.GroupRow("__new__", "Nova playlist", "Criar uma playlist", R.drawable.ic_add)
                playlists.getAll().forEach { p ->
                    if (q.isEmpty() || p.name.lowercase().contains(q))
                        rows += Row.GroupRow("pl:${p.name}", p.name, "${p.songIds.size} músicas", R.drawable.ic_playlist)
                }
            }
        }
        return rows
    }

    private fun songsForDrill(): List<Song> {
        val key = drillKey ?: return emptyList()
        return when (tab) {
            1 -> allSongs.filter { (if (it.album.isBlank()) "Álbum desconhecido" else it.album) == key }
            2 -> allSongs.filter { (if (it.artist.isBlank() || it.artist == "<unknown>") "Artista desconhecido" else it.artist) == key }
            3 -> allSongs.filter { it.folder == key }
            4 -> {
                val name = key.removePrefix("pl:")
                val p = playlists.getAll().find { it.name == name } ?: return emptyList()
                p.songIds.mapNotNull { id -> allSongs.find { it.id == id } }
            }
            else -> emptyList()
        }
    }

    // ---------------- Row interaction ----------------
    private fun onRowClick(row: Row) {
        when (row) {
            is Row.GroupRow -> {
                if (row.key == "__new__") { showCreatePlaylistDialog(); return }
                drillKey = row.key; binding.searchInput.setText(""); refresh()
            }
            is Row.SongRow -> {
                val idx = queueOnPlayList.indexOfFirst { it.id == row.song.id }
                if (idx >= 0) { playAt(queueOnPlayList, idx); openNowPlaying() }
            }
        }
    }

    private fun onRowLongClick(row: Row) {
        when (row) {
            is Row.SongRow -> {
                val inPlaylist = tab == 4 && drillKey != null
                if (inPlaylist) {
                    val plName = drillKey!!.removePrefix("pl:")
                    AlertDialog.Builder(this).setTitle(row.song.title)
                        .setItems(arrayOf("Adicionar a outra playlist", "Remover desta playlist")) { _, w ->
                            if (w == 0) choosePlaylistToAdd(row.song.id)
                            else { playlists.removeSong(plName, row.song.id); refresh() }
                        }.show()
                } else choosePlaylistToAdd(row.song.id)
            }
            is Row.GroupRow -> {
                if (row.key.startsWith("pl:")) {
                    val name = row.key.removePrefix("pl:")
                    AlertDialog.Builder(this).setTitle(name)
                        .setItems(arrayOf("Renomear", "Excluir")) { _, w ->
                            if (w == 0) showRenamePlaylistDialog(name) else { playlists.delete(name); refresh() }
                        }.show()
                }
            }
        }
    }

    private fun choosePlaylistToAdd(songId: Long) {
        val all = playlists.getAll()
        if (all.isEmpty()) { showCreatePlaylistDialog(songId); return }
        val names = all.map { it.name }.toMutableList()
        names.add("＋ Nova playlist")
        AlertDialog.Builder(this).setTitle("Adicionar à playlist")
            .setItems(names.toTypedArray()) { _, w ->
                if (w == names.size - 1) showCreatePlaylistDialog(songId)
                else { playlists.addSong(names[w], songId); toast("Adicionada a \"${names[w]}\""); refresh() }
            }.show()
    }

    private fun showCreatePlaylistDialog(songToAdd: Long? = null) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT; hint = "Nome da playlist"; setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this).setTitle("Nova playlist").setView(input)
            .setPositiveButton("Criar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (playlists.create(name)) {
                    if (songToAdd != null) playlists.addSong(name, songToAdd)
                    toast("Playlist \"$name\" criada"); refresh()
                } else toast("Já existe uma playlist com esse nome")
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showRenamePlaylistDialog(oldName: String) {
        val input = EditText(this).apply {
            setText(oldName); inputType = InputType.TYPE_CLASS_TEXT; setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this).setTitle("Renomear playlist").setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (playlists.rename(oldName, name)) {
                    if (drillKey == "pl:$oldName") drillKey = "pl:$name"; refresh()
                } else toast("Nome já usado")
            }.setNegativeButton("Cancelar", null).show()
    }

    // ---------------- Now Playing screen ----------------
    private fun openNowPlaying() {
        binding.nowPlaying.visibility = android.view.View.VISIBLE
        binding.nowPlaying.alpha = 0f
        binding.nowPlaying.animate().alpha(1f).setDuration(180).start()
    }

    private fun closeNowPlaying() {
        binding.nowPlaying.animate().alpha(0f).setDuration(150).withEndAction {
            binding.nowPlaying.visibility = android.view.View.GONE
        }.start()
    }

    // ---------------- Player ----------------
    private fun setupPlayer() {
        player.setOnCompletionListener { nextTrack(auto = true) }
        player.setOnPreparedListener {
            prepared = true
            it.start()
            binding.npSeek.max = it.duration
            binding.miniProgress.max = it.duration
            binding.npDur.text = formatTime(it.duration.toLong())
            updatePlayIcon()
            startSeekUpdater()
        }
    }

    private fun setupControls() {
        val toggle = {
            if (queueIndex == -1 && queueOnPlayList.isNotEmpty()) { playAt(queueOnPlayList, 0); openNowPlaying() }
            else { if (player.isPlaying) player.pause() else if (prepared) player.start(); updatePlayIcon() }
        }
        binding.npPlaypause.setOnClickListener { toggle() }
        binding.miniPlaypause.setOnClickListener { toggle() }

        binding.npNext.setOnClickListener { nextTrack(auto = false) }
        binding.npPrev.setOnClickListener { prevTrack() }

        binding.npShuffle.setOnClickListener {
            shuffle = !shuffle; history.clear(); updateShuffleIcon()
            toast(if (shuffle) "Aleatório ligado" else "Aleatório desligado")
        }
        binding.npRepeat.setOnClickListener {
            repeatMode = (repeatMode + 1) % 3; updateRepeatIcon()
            toast(when (repeatMode) { 1 -> "Repetir todas"; 2 -> "Repetir esta música"; else -> "Repetir desligado" })
        }

        binding.miniBar.setOnClickListener { openNowPlaying() }
        binding.btnNpClose.setOnClickListener { closeNowPlaying() }
        binding.btnNpAdd.setOnClickListener {
            if (queueIndex in queue.indices) choosePlaylistToAdd(queue[queueIndex].id)
        }

        binding.npSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) binding.npCur.text = formatTime(p.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { if (prepared) player.seekTo(sb?.progress ?: 0) }
        })

        updateShuffleIcon(); updateRepeatIcon()
    }

    private fun updateShuffleIcon() {
        binding.npShuffle.setColorFilter(if (shuffle) 0xFFF6C915.toInt() else 0xFF9797A8.toInt())
    }

    private fun updateRepeatIcon() {
        binding.npRepeat.setImageResource(if (repeatMode == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        binding.npRepeat.setColorFilter(if (repeatMode != 0) 0xFFF6C915.toInt() else 0xFF9797A8.toInt())
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun playAt(list: List<Song>, index: Int) {
        if (index < 0 || index >= list.size) return
        queue = list.toList(); queueIndex = index; history.clear()
        loadAndPlay(queue[queueIndex])
    }

    private fun loadAndPlay(song: Song) {
        try {
            prepared = false
            player.reset()
            player.setDataSource(this, song.uri)
            player.prepareAsync()
            binding.miniBar.visibility = android.view.View.VISIBLE
            updateNowPlayingUI(song)
            adapter.setPlaying(song.id)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun updateNowPlayingUI(song: Song) {
        val artistTxt = if (song.artist.isBlank() || song.artist == "<unknown>") "Artista desconhecido" else song.artist
        binding.miniTitle.text = song.title
        binding.miniArtist.text = artistTxt
        binding.npBigTitle.text = song.title
        binding.npBigArtist.text = artistTxt
        val bmp = loadArtBitmap(song)
        if (bmp != null) {
            binding.miniArt.setImageBitmap(bmp); binding.npBigArt.setImageBitmap(bmp)
        } else {
            binding.miniArt.setImageResource(R.drawable.ic_note); binding.npBigArt.setImageResource(R.drawable.ic_note)
        }
    }

    private fun loadArtBitmap(song: Song): Bitmap? {
        val uri = ContentUris.withAppendedId(artBase, song.albumId)
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
            }
        } catch (e: Exception) { null }
    }

    private fun nextTrack(auto: Boolean) {
        if (queue.isEmpty()) return
        if (repeatMode == 2 && auto) { player.seekTo(0); player.start(); return }
        when {
            shuffle && queue.size > 1 -> {
                history.addLast(queueIndex)
                var r: Int
                do { r = Random.nextInt(queue.size) } while (r == queueIndex)
                queueIndex = r
            }
            queueIndex < queue.size - 1 -> queueIndex++
            repeatMode == 1 -> queueIndex = 0
            else -> { player.pause(); updatePlayIcon(); return }
        }
        loadAndPlay(queue[queueIndex])
    }

    private fun prevTrack() {
        if (queue.isEmpty()) return
        if (prepared && player.currentPosition > 3000) { player.seekTo(0); return }
        when {
            shuffle && history.isNotEmpty() -> queueIndex = history.removeLast()
            queueIndex > 0 -> queueIndex--
            repeatMode == 1 -> queueIndex = queue.size - 1
            else -> { player.seekTo(0); return }
        }
        loadAndPlay(queue[queueIndex])
    }

    private fun updatePlayIcon() {
        val res = if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        binding.npPlaypause.setImageResource(res)
        binding.miniPlaypause.setImageResource(res)
    }

    private val seekRunnable = object : Runnable {
        override fun run() {
            if (prepared && player.isPlaying) {
                val pos = player.currentPosition
                binding.npSeek.progress = pos
                binding.miniProgress.progress = pos
                binding.npCur.text = formatTime(pos.toLong())
            }
            handler.postDelayed(this, 500)
        }
    }
    private fun startSeekUpdater() { handler.removeCallbacks(seekRunnable); handler.post(seekRunnable) }

    private fun formatTime(ms: Long): String { val t = ms / 1000; return "%d:%02d".format(t / 60, t % 60) }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, permissionName()) == PackageManager.PERMISSION_GRANTED) loadSongs()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(seekRunnable)
        observer?.let { contentResolver.unregisterContentObserver(it) }
        player.release()
    }
}
