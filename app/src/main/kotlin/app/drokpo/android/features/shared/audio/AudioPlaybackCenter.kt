package app.drokpo.android.features.shared.audio

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resumeWithException

/**
 * Plays one voice clip at a time, app-wide — starting a new clip stops
 * whatever was already playing. Downloads remote audio to a small file cache
 * keyed by URL (voice clips are short; re-playing the same clip skips the
 * network). Port of iOS `AudioPlaybackCenter`, on Media3 ExoPlayer.
 */
object AudioPlaybackCenter {
    private val controller = PlaybackController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        engineFactory = {
            stopWhenAppBackgrounds()
            ExoPlaybackEngine(AppGraph.app)
        },
        resolve = { url -> VoiceClipCache.playableSource(AppGraph.app, url) },
    )

    val playingId: StateFlow<String?> = controller.playingId

    /** 0..1 of the playing clip, updated ~100 ms. */
    val progress: StateFlow<Float> = controller.progress

    /**
     * Tapping the clip that's already playing stops it (a pause gesture);
     * tapping any other clip stops the current one and starts the new one
     * (https → downloaded once to cacheDir/voice-cache/{hash}.m4a; file:// →
     * played directly). Silent on failure — a failed voice-clip playback
     * shouldn't surface an alert.
     */
    fun play(id: String, url: String) = controller.play(id, url)

    fun stop() = controller.stop()

    /**
     * iOS has no background-audio mode, so a clip stops when the app leaves the
     * foreground; ExoPlayer would otherwise keep talking from the background.
     */
    private fun stopWhenAppBackgrounds() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) stop()
            },
        )
    }
}

/** The player behind [PlaybackController] (ExoPlayer in the app, a fake in tests). */
internal interface PlaybackEngine {
    /** Starts playing [source] (a local file path / URI string). Exactly one of the callbacks may fire later. */
    fun start(source: String, onEnded: () -> Unit, onError: () -> Unit)

    fun stop()

    /** currentTime / duration while playing (0 when the duration is unknown), null while not playing. */
    fun currentProgress(): Float?
}

/**
 * The single-clip state machine of [AudioPlaybackCenter], separated from
 * ExoPlayer so it runs in JVM tests.
 *
 * Unlike iOS — whose fire-and-forget `Task` let two quick taps start two
 * players — the fetch-and-start of the previous clip is cancelled by [stop],
 * so only one clip can ever be heard.
 */
internal class PlaybackController(
    private val scope: CoroutineScope,
    private val engineFactory: () -> PlaybackEngine,
    private val resolve: suspend (String) -> String,
    private val tickMillis: Long = 100,
) {
    private val _playingId = MutableStateFlow<String?>(null)
    private val _progress = MutableStateFlow(0f)
    val playingId: StateFlow<String?> = _playingId.asStateFlow()
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private var engine: PlaybackEngine? = null
    private var startJob: Job? = null
    private var progressJob: Job? = null

    /** Bumped by every play/stop so callbacks from an older clip are ignored. */
    private var session = 0

    /** The clip being fetched/started but not audible yet. */
    private var pendingId: String? = null

    fun play(id: String, url: String) {
        if (_playingId.value == id) {
            stop()
            return
        }
        // A second tap while the same clip is still downloading keeps that
        // download instead of restarting it (iOS shows no "loading" state either).
        if (pendingId == id) return
        stop()
        val token = session
        pendingId = id
        startJob = scope.launch {
            try {
                val source = resolve(url)
                if (token != session) return@launch
                val player = engine ?: engineFactory().also { engine = it }
                player.start(
                    source,
                    onEnded = { if (token == session) stop() },
                    onError = { if (token == session) stop() },
                )
                if (token != session) return@launch
                pendingId = null
                _playingId.value = id
                startProgressLoop(player, token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent — a failed voice-clip playback shouldn't surface an alert.
                if (token == session) pendingId = null
            }
        }
    }

    fun stop() {
        session++
        startJob?.cancel()
        startJob = null
        progressJob?.cancel()
        progressJob = null
        pendingId = null
        engine?.stop()
        _playingId.value = null
        _progress.value = 0f
    }

    private fun startProgressLoop(player: PlaybackEngine, token: Int) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && token == session) {
                delay(tickMillis)
                if (token != session) return@launch
                player.currentProgress()?.let { _progress.value = it.coerceIn(0f, 1f) }
            }
        }
    }
}

/**
 * Media3 ExoPlayer, created once and reused. `handleAudioFocus = false` mirrors
 * iOS `.playback` + `.mixWithOthers`: a voice clip never pauses the user's music.
 */
internal class ExoPlaybackEngine(context: Context) : PlaybackEngine {
    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ false,
        )
        .build()

    private var onEnded: (() -> Unit)? = null
    private var onError: (() -> Unit)? = null

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
                }

                override fun onPlayerError(error: PlaybackException) {
                    onError?.invoke()
                }
            },
        )
    }

    override fun start(source: String, onEnded: () -> Unit, onError: () -> Unit) {
        this.onEnded = onEnded
        this.onError = onError
        player.setMediaItem(MediaItem.fromUri(source))
        player.prepare()
        player.play()
    }

    override fun stop() {
        onEnded = null
        onError = null
        player.stop()
        player.clearMediaItems()
    }

    override fun currentProgress(): Float? {
        if (!player.isPlaying) return null
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return 0f
        return player.currentPosition.toFloat() / duration
    }
}

/** cacheDir/voice-cache — downloaded voice clips, keyed by a hash of their URL. */
internal object VoiceClipCache {
    /**
     * A composer's just-recorded preview clip is already a local file — play it
     * directly instead of round-tripping it through the cache. Remote clips are
     * downloaded once; later plays read the cached copy.
     */
    suspend fun playableSource(context: Context, url: String): String {
        val scheme = url.toUri().scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return url
        val file = cachedFile(context.cacheDir, url)
        if (file.exists() && file.length() > 0) return Uri.fromFile(file).toString()
        download(url, file)
        return Uri.fromFile(file).toString()
    }

    internal fun cachedFile(cacheDir: File, url: String): File =
        File(File(cacheDir, DIRECTORY), "${cacheKey(url)}.m4a")

    /** Stable across launches (iOS used Swift's per-process `hashValue`, so its cache only lasted a session). */
    internal fun cacheKey(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }

    private suspend fun download(url: String, target: File) {
        val response = ApiClient.defaultHttpClient.newCall(Request.Builder().url(url).build()).await()
        withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                target.parentFile?.mkdirs()
                // Write next to the target and rename, so a cancelled or failed
                // download never leaves a truncated clip in the cache.
                val partial = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.part")
                try {
                    partial.outputStream().use { out -> it.body.byteStream().copyTo(out) }
                    if (!partial.renameTo(target)) throw IOException("Couldn't cache the voice clip")
                } finally {
                    partial.delete()
                }
            }
        }
    }

    private const val DIRECTORY = "voice-cache"
}

/** Suspends until the call completes; cancelling the coroutine cancels the HTTP call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Throwable) {
            // Cancellation is best-effort.
        }
    }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        },
    )
}
