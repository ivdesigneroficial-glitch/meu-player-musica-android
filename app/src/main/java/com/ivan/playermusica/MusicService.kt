package com.ivan.playermusica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import kotlin.random.Random

class MusicService : android.app.Service() {

    inner class LocalBinder : Binder() { fun getService(): MusicService = this@MusicService }
    private val binder = LocalBinder()

    val player = MediaPlayer()
    var prepared = false; private set

    var queue: List<Song> = emptyList(); private set
    var queueIndex = -1; private set
    var shuffle = false
    var repeatMode = 0 // 0 off, 1 all, 2 one
    private val history = ArrayDeque<Int>()

    var onChanged: (() -> Unit)? = null

    private lateinit var session: MediaSessionCompat
    private val artBase: Uri = Uri.parse("content://media/external/audio/albumart")
    private var appIcon: Bitmap? = null

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIF_ID = 1
        const val ACTION_PLAY_PAUSE = "com.ivan.playermusica.PLAY_PAUSE"
        const val ACTION_NEXT = "com.ivan.playermusica.NEXT"
        const val ACTION_PREV = "com.ivan.playermusica.PREV"
        const val ACTION_STOP = "com.ivan.playermusica.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        appIcon = try { BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher) } catch (e: Exception) { null }

        player.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .build()
        )
        player.setOnCompletionListener { next(auto = true) }
        player.setOnPreparedListener {
            prepared = true
            it.start()
            updateSessionAndNotification()
            onChanged?.invoke()
        }

        session = MediaSessionCompat(this, "MeuPlayer")
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { play() }
            override fun onPause() { pause() }
            override fun onSkipToNext() { next(false) }
            override fun onSkipToPrevious() { prev() }
            override fun onStop() { stopPlayback() }
            override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
        })
        session.isActive = true

        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> next(false)
            ACTION_PREV -> prev()
            ACTION_STOP -> stopPlayback()
            else -> updateSessionAndNotification()
        }
        return START_NOT_STICKY
    }

    // ---------- Controls ----------
    fun setQueue(list: List<Song>, index: Int) {
        if (index < 0 || index >= list.size) return
        queue = list.toList(); queueIndex = index; history.clear()
        loadAndPlay(queue[queueIndex])
    }

    fun currentSong(): Song? = queue.getOrNull(queueIndex)
    fun isPlaying(): Boolean = try { player.isPlaying } catch (e: Exception) { false }
    fun position(): Int = if (prepared) try { player.currentPosition } catch (e: Exception) { 0 } else 0
    fun duration(): Int = if (prepared) try { player.duration } catch (e: Exception) { 0 } else 0

    fun togglePlayPause() { if (isPlaying()) pause() else play() }

    fun play() {
        if (prepared) { player.start(); updateSessionAndNotification(); onChanged?.invoke() }
    }

    fun pause() {
        if (prepared && player.isPlaying) { player.pause(); updateSessionAndNotification(); onChanged?.invoke() }
    }

    fun seekTo(ms: Int) { if (prepared) player.seekTo(ms) }

    private fun loadAndPlay(song: Song) {
        try {
            prepared = false
            player.reset()
            player.setDataSource(this, song.uri)
            player.prepareAsync()
            onChanged?.invoke()
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun next(auto: Boolean) {
        if (queue.isEmpty()) return
        if (repeatMode == 2 && auto) { player.seekTo(0); player.start(); updateSessionAndNotification(); return }
        when {
            shuffle && queue.size > 1 -> {
                history.addLast(queueIndex)
                var r: Int
                do { r = Random.nextInt(queue.size) } while (r == queueIndex)
                queueIndex = r
            }
            queueIndex < queue.size - 1 -> queueIndex++
            repeatMode == 1 -> queueIndex = 0
            else -> { pause(); return }
        }
        loadAndPlay(queue[queueIndex])
    }

    fun prev() {
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

    private fun stopPlayback() {
        try { player.pause() } catch (e: Exception) {}
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        onChanged?.invoke()
        stopSelf()
    }

    // ---------- Album art ----------
    private fun artFor(song: Song): Bitmap? {
        val uri = ContentUris.withAppendedId(artBase, song.albumId)
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
            }
        } catch (e: Exception) { null }
    }

    // ---------- MediaSession + Notification ----------
    private fun updateSessionAndNotification() {
        val song = currentSong() ?: return
        val art = artFor(song) ?: appIcon
        val artistTxt = if (song.artist.isBlank() || song.artist == "<unknown>") "Artista desconhecido" else song.artist

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artistTxt)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (prepared) player.duration.toLong() else song.duration)
        if (art != null) metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
        session.setMetadata(metadata.build())

        val playing = isPlaying()
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                position().toLong(), 1f
            ).build()
        session.setPlaybackState(state)

        val notif = buildNotification(song, artistTxt, art, playing)
        if (playing) {
            startForegroundCompat(notif)
        } else {
            // keep the notification but allow dismissal when paused
            startForegroundCompat(notif)
            stopForeground(STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif)
        }
    }

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(song: Song, artist: String, art: Bitmap?, playing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(song.title)
            .setContentText(artist)
            .setLargeIcon(art)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(0xFFF6C915.toInt())

        builder.addAction(NotificationCompat.Action(R.drawable.ic_skip_prev, "Anterior", servicePendingIntent(ACTION_PREV)))
        if (playing)
            builder.addAction(NotificationCompat.Action(R.drawable.ic_pause, "Pausar", servicePendingIntent(ACTION_PLAY_PAUSE)))
        else
            builder.addAction(NotificationCompat.Action(R.drawable.ic_play_arrow, "Tocar", servicePendingIntent(ACTION_PLAY_PAUSE)))
        builder.addAction(NotificationCompat.Action(R.drawable.ic_skip_next, "Próxima", servicePendingIntent(ACTION_NEXT)))

        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(servicePendingIntent(ACTION_STOP))
        )
        builder.setOngoing(playing)
        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Reprodução", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session.release()
        player.release()
    }
}
