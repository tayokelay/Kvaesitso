package de.mm20.launcher2.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaSession
import android.net.Uri
import android.service.notification.StatusBarNotification
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.size.Scale
import de.mm20.launcher2.crashreporter.CrashReporter
import de.mm20.launcher2.notifications.NotificationRepository
import de.mm20.launcher2.preferences.LauncherDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import java.io.IOException

class Media3MusicService(
    private val context: Context,
    private val dataStore: LauncherDataStore,
    notificationRepository: NotificationRepository,
) : MusicService, KoinComponent {

    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override var lastPlayerPackage: String? = null
        get() {
            if (field == null) {
                field = preferences.getString(PREFS_KEY_LAST_PLAYER, null)
            }
            return field
        }
        set(value) {
            preferences.edit {
                putString(PREFS_KEY_LAST_PLAYER, value)
            }
            field = value
        }

    private val currentMediaController: SharedFlow<MediaController?> =
        combine(
            notificationRepository.notifications,
            dataStore.data.map { it.musicWidget.filterSources }
        ) { notifications, filter ->
            withContext(Dispatchers.Default) {
                val musicApps = null // if (filter) getMusicApps() else null
                val sbn: StatusBarNotification? = notifications.filter {
                    it.notification.extras.getParcelable<MediaSession.Token>(NotificationCompat.EXTRA_MEDIA_SESSION) is MediaSession.Token &&
                            (musicApps?.contains(it.packageName) != false)
                }.maxByOrNull { it.postTime }

                return@withContext sbn
            }
        }
            .distinctUntilChanged()
            .map { sbn ->
                if (sbn == null) return@map null
                val token =
                    sbn.notification?.extras?.getParcelable<MediaSession.Token>(NotificationCompat.EXTRA_MEDIA_SESSION)
                        ?: return@map null

                // Why, Google?! Why do you have to make this so complicated? Why can't you just use the same token for everything?
                // I mean, I get that you want to make it harder for people to use your API, but this is just ridiculous. (~(c) Github Copilot, 2023)

                val compatToken = MediaSessionCompat.Token.fromToken(token)

                return@map withContext(Dispatchers.IO) {
                    val sessionToken = SessionToken.createSessionToken(context, compatToken).get()
                    lastPlayerPackage = sessionToken.packageName
                    MediaController.Builder(context, sessionToken)
                        .buildAsync().get()
                }

            }
            .shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    private val currentMetadata: SharedFlow<MediaMetadata?> = channelFlow {
        currentMediaController.collectLatest { controller ->
            if (controller == null) {
                send(null)
                return@collectLatest
            }

            val sessionCommands = withContext(Dispatchers.Main) { controller.availableSessionCommands }
            val commands = withContext(Dispatchers.Main) { controller.availableCommands }
            Log.d("MM20", "commands: ${sessionCommands.commands.map { it.commandCode }.joinToString()}")
            Log.d("MM20", "commands: ${commands.contains(Player.COMMAND_SET_SHUFFLE_MODE)}")
            send(withContext(Dispatchers.Main) {
                controller.mediaMetadata
            })
            val listener = object: Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    super.onMediaMetadataChanged(mediaMetadata)
                    trySend(mediaMetadata)
                }


                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    super.onAvailableCommandsChanged(availableCommands)
                    Log.d("MM20", "commands: ${availableCommands.contains(Player.COMMAND_SET_SHUFFLE_MODE)}")
                }
            }
            try {
                withContext(Dispatchers.Main) {
                    controller.addListener(listener)
                }
                awaitCancellation()
            } finally {
                withContext(Dispatchers.Main) {
                    controller.removeListener(listener)
                }
            }
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    private var lastTitle: String? = null
        get() {
            if (field == null) {
                field = preferences.getString(PREFS_KEY_TITLE, null)
            }
            return field
        }
        set(value) {
            preferences.edit {
                putString(PREFS_KEY_TITLE, value)
            }
            field = value
        }

    override val title: Flow<String?> = channelFlow {
        currentMetadata.collectLatest { metadata ->
            if (metadata == null) {
                send(lastTitle)
                return@collectLatest
            }
            val title = metadata.title?.toString()
                ?: metadata.displayTitle?.toString()
                ?: currentMediaController.firstOrNull()?.connectedToken?.packageName?.let { pkg ->
                    getAppLabel(pkg)?.let {
                        context.getString(
                            R.string.music_widget_default_title,
                            it
                        )
                    }
                }
            lastTitle = title
            send(title)
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    private var lastArtist: String? = null
        get() {
            if (field == null) {
                field = preferences.getString(PREFS_KEY_ARTIST, null)
            }
            return field
        }
        set(value) {
            preferences.edit {
                putString(PREFS_KEY_ARTIST, value)
            }
            field = value
        }

    override val artist: Flow<String?> = channelFlow {
        currentMetadata.collectLatest { metadata ->
            if (metadata == null) {
                send(lastArtist)
                return@collectLatest
            }

            val artist = metadata.artist?.toString()
                ?: metadata.subtitle?.toString()
                ?: currentMediaController.firstOrNull()?.connectedToken?.packageName?.let { pkg ->
                    getAppLabel(pkg)
                }
            lastArtist = artist
            send(artist)
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    private var lastAlbum: String? = null
        get() {
            if (field == null) {
                field = preferences.getString(PREFS_KEY_ALBUM, null)
            }
            return field
        }
        set(value) {
            preferences.edit {
                putString(PREFS_KEY_ALBUM, value)
            }
            field = value
        }

    private var lastAlbumArt: Uri? = null
        get() {
            if (field == null) {
                field = preferences.getString(PREFS_KEY_ALBUM_ART, null)?.let { Uri.parse(it) }
            }
            return field
        }
        set(value) {
            preferences.edit {
                putString(PREFS_KEY_ALBUM, value?.toString())
            }
            field = value
        }
    override val album = channelFlow {
        currentMetadata.collectLatest { metadata ->
            if (metadata == null) {
                send(lastAlbum)
                return@collectLatest
            }

            val album = metadata.albumTitle?.toString()
            lastAlbum = album
            send(album)
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)


    override val albumArt: Flow<Bitmap?> = channelFlow {
        val size = context.resources.getDimensionPixelSize(R.dimen.album_art_size)
        currentMetadata
            .map {
                if (it == null) lastAlbumArt
                else {
                    lastAlbumArt = it.artworkUri

                    it.artworkUri
                }
            }
            .distinctUntilChanged()
            .collectLatest { uri ->
            val bitmap = uri?.let { loadBitmapFromUri(it, size) }.also { Log.d("MM20", "Bitmap was ${if (it == null) "not " else ""}loaded") }
            send(bitmap)
        }
    }

    private val currentTimeline: Flow<Timeline?> = channelFlow {
        currentMediaController.collectLatest { controller ->
            if (controller == null) {
                send(null)
                return@collectLatest
            }
            send(withContext(Dispatchers.Main) {
                controller.currentTimeline
            })
            val listener = object: Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    super.onTimelineChanged(timeline, reason)
                    trySend(timeline)
                }
            }
            try {
                withContext(Dispatchers.Main) {
                    controller.addListener(listener)
                }
                awaitCancellation()
            } finally {
                withContext(Dispatchers.Main) {
                    controller.removeListener(listener)
                }
            }
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    private var lastDuration: Long? = null
        get() {
            if (field == null) {
                field = preferences.getLong(PREFS_KEY_DURATION, -1).takeIf { it > 0 }
            }
            return field
        }
        set(value) {
            preferences.edit {
                putLong(PREFS_KEY_DURATION, value ?: -1)
            }
            field = value
        }

    override val duration: Flow<Long?> = channelFlow {
        currentMediaController.collectLatest { controller ->
            if (controller == null) {
                send(lastDuration)
                return@collectLatest
            }
            send(withContext(Dispatchers.Main) {
                controller.duration
            })
            val listener = object: Player.Listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    super.onAvailableCommandsChanged(availableCommands)
                }
            }
            try {
                withContext(Dispatchers.Main) {
                    controller.addListener(listener)
                }
                awaitCancellation()
            } finally {
                withContext(Dispatchers.Main) {
                    controller.removeListener(listener)
                }
            }
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)

    override val position: Flow<Long?> = flowOf(null)

    override val playbackState: Flow<PlaybackState> = flowOf(PlaybackState.Stopped)

    override val supportedActions: Flow<SupportedActions> = flowOf(SupportedActions())

    override fun play() {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            if (controller != null) {
                controller.play()
            } else {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
                audioManager.dispatchMediaKeyEvent(downEvent)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
        }
    }

    override fun pause() {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            if (controller != null) {
                controller.pause()
            } else {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                audioManager.dispatchMediaKeyEvent(downEvent)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
        }
    }

    override fun togglePause() {
    }

    override fun next() {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            if (controller != null) {
                controller.seekToNext()
            } else {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
                audioManager.dispatchMediaKeyEvent(downEvent)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
        }
    }

    override fun previous() {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            if (controller != null) {
                controller.seekToPrevious()
            } else {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                audioManager.dispatchMediaKeyEvent(downEvent)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                audioManager.dispatchMediaKeyEvent(upEvent)
            }
        }
    }

    override fun seekTo(position: Long) {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
            controller?.seekTo(position)
        }
    }

    override fun performCustomAction(action: android.media.session.PlaybackState.CustomAction) {
        scope.launch {
            val controller = currentMediaController.firstOrNull()
        }
    }

    override fun openPlayer(): PendingIntent? {

        val controller = currentMediaController.replayCache.firstOrNull()

        controller?.sessionActivity?.let {
            return it
        }

        val packageName = controller?.connectedToken?.packageName ?: lastPlayerPackage

        val intent = packageName?.let {
            context.packageManager.getLaunchIntentForPackage(it)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } ?: return null

        if (context.packageManager.resolveActivity(intent, 0) == null) {
            return null
        }

        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun openPlayerChooser(context: Context) {
        context.startActivity(
            Intent.createChooser(
                Intent("android.intent.action.MUSIC_PLAYER")
                    .apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    },
                null
            )
        )
    }

    override fun resetPlayer() {
        scope.launch {
            preferences.edit {
                clear()
            }
        }
    }



    private fun getAppLabel(packageName: String): String? {
        return try {
            context
                .packageManager
                .getPackageInfo(packageName, 0).applicationInfo
                .loadLabel(context.packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private suspend fun loadBitmapFromUri(uri: Uri, size: Int): Bitmap? {
        var bitmap: Bitmap? = null
        try {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(size)
                .scale(Scale.FILL)
                .target {
                    bitmap = it.toBitmap()
                }
                .build()
            val result = context.imageLoader.execute(request)
            Log.d("MM20", (result as? ErrorResult)?.throwable?.stackTraceToString().toString())
        } catch (e: IOException) {
            CrashReporter.logException(e)
        } catch (e: SecurityException) {
            CrashReporter.logException(e)
        }
        return bitmap
    }

    companion object {

        private const val PREFS = "music"
        private const val PREFS_KEY_TITLE = "title"
        private const val PREFS_KEY_DURATION = "duration"
        private const val PREFS_KEY_POSITION = "position"
        private const val PREFS_KEY_ARTIST = "artist"
        private const val PREFS_KEY_ALBUM = "album"
        private const val PREFS_KEY_ALBUM_ART = "album_art_uri"
        private const val PREFS_KEY_LAST_PLAYER = "last_player"
    }
}