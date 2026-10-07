package app.drokpo.android.features.shared.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** A finished voice recording: the temp .m4a file and its length in whole seconds (≥ 1). */
data class RecordedClip(val file: File, val seconds: Int)

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(val elapsedSeconds: Int) : RecorderState
    data class Failed(val message: String) : RecorderState
}

/**
 * Records a voice clip to a temp .m4a (AAC) file — port of iOS `AudioRecorder`.
 * One instance per composer/input bar ([rememberAudioRecorder]); call [start]
 * then either [stop] or [cancel] — never both concurrently on the same instance.
 *
 * Output matches iOS: `cacheDir/{uuid}.m4a`, MPEG-4 container, AAC, 44.1 kHz,
 * mono (iOS `AVAudioQuality.medium` ≈ 64 kbps). The storage rules cap comment
 * audio at 5 MB and the backend at 60 s, so a 60 s / 120 s clip fits easily.
 */
class AudioRecorder internal constructor(
    private val engine: RecordingEngine,
    private val permission: MicPermission,
    private val scope: CoroutineScope,
    private val newFile: () -> File,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var file: File? = null
    private var startedAt: Long? = null
    private var timer: Job? = null
    private var maxSeconds: Int = DEFAULT_MAX_SECONDS
    private var onAutoStop: ((RecordedClip) -> Unit)? = null

    /** Bumped by every start/cancel/release, so a permission answer that arrives late is ignored. */
    private var permissionRequest = 0
    private var awaitingPermission = false
    private var released = false

    /**
     * Requests the mic permission if needed, then starts recording to a fresh
     * temp file. Auto-stops (and calls [onAutoStop]) once [maxSeconds] is hit.
     * A denial lands in [RecorderState.Failed] — which the caller shows with a
     * dismiss button ([RecorderFailureRow] → [dismissFailure]) — never in a state
     * that hides the text input for good.
     */
    fun start(maxSeconds: Int, onAutoStop: (RecordedClip) -> Unit) {
        if (released || file != null || awaitingPermission) return
        this.maxSeconds = maxSeconds
        this.onAutoStop = onAutoStop
        if (permission.isGranted()) {
            beginRecording()
            return
        }
        val request = ++permissionRequest
        awaitingPermission = true
        permission.request { granted ->
            if (request != permissionRequest) return@request
            awaitingPermission = false
            if (released) return@request
            if (granted) {
                beginRecording()
            } else {
                _state.value = RecorderState.Failed(MIC_OFF_MESSAGE)
            }
        }
    }

    private fun beginRecording() {
        val target = newFile()
        try {
            engine.start(target, onError = ::onEngineError)
        } catch (e: RecorderSetupException) {
            target.delete()
            _state.value = RecorderState.Failed(e.userMessage)
            return
        }
        file = target
        startedAt = clock()
        _state.value = RecorderState.Recording(elapsedSeconds = 0)
        startTimer()
    }

    private fun startTimer() {
        timer?.cancel()
        timer = scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                val start = startedAt ?: return@launch
                val elapsed = elapsedSeconds(start)
                _state.value = RecorderState.Recording(elapsed)
                if (elapsed >= maxSeconds) {
                    val clip = stopInternal(cancelTimer = false)
                    if (clip != null) onAutoStop?.invoke(clip)
                    return@launch
                }
            }
        }
    }

    private fun elapsedSeconds(start: Long): Int = ((clock() - start) / 1000.0).roundToInt()

    /**
     * Stops recording and returns the file + duration in seconds (at least 1,
     * at most the max passed to [start] — the backend rejects longer clips), or
     * null if nothing was recording.
     */
    fun stop(): RecordedClip? = stopInternal(cancelTimer = true)

    private fun stopInternal(cancelTimer: Boolean): RecordedClip? {
        if (cancelTimer) timer?.cancel()
        timer = null
        val recording = file
        val start = startedAt
        if (recording == null || start == null) {
            _state.value = RecorderState.Idle
            return null
        }
        val seconds = elapsedSeconds(start).coerceIn(1, maxOf(1, maxSeconds))
        // MediaRecorder.stop() throws when almost nothing was captured (a tap
        // on stop right after start) — the file is unusable then.
        val usable = engine.stop()
        file = null
        startedAt = null
        _state.value = RecorderState.Idle
        if (!usable) {
            recording.delete()
            return null
        }
        return RecordedClip(recording, seconds)
    }

    /**
     * Clears a [RecorderState.Failed] state so the composer shows its normal
     * input again — a denied mic permission must never lock the user out of typing.
     */
    fun dismissFailure() {
        if (_state.value is RecorderState.Failed) _state.value = RecorderState.Idle
    }

    /** Stops and discards the recording — used when the user cancels. */
    fun cancel() {
        permissionRequest++
        awaitingPermission = false
        timer?.cancel()
        timer = null
        file?.let { recording ->
            engine.stop()
            recording.delete()
        }
        file = null
        startedAt = null
        _state.value = RecorderState.Idle
    }

    /**
     * The app left the foreground. Android hands a backgrounded app silence
     * from the mic (no microphone foreground service) while the timer would
     * keep counting to the limit, so the recording ends here and what was
     * captured goes to the `onAutoStop` passed to [start] — the composer shows
     * it as a preview, exactly like hitting the limit. Nothing usable → Idle.
     * No-op unless recording.
     */
    internal fun finishForBackground() {
        if (file == null) return
        val clip = stopInternal(cancelTimer = true)
        if (clip != null) onAutoStop?.invoke(clip)
    }

    /** Cancels anything in progress and frees the MediaRecorder; the instance is dead afterwards. */
    internal fun release() {
        cancel()
        released = true
        engine.release()
    }

    private fun onEngineError() {
        if (file == null) return
        cancel()
        _state.value = RecorderState.Failed(START_FAILED_MESSAGE)
    }

    internal companion object {
        const val DEFAULT_MAX_SECONDS = 60
        const val TICK_MILLIS = 1_000L
        const val MIC_OFF_MESSAGE = "Microphone access is off. You can turn it on in Settings."
        const val MIC_UNAVAILABLE_MESSAGE = "Couldn't access the microphone."
        const val START_FAILED_MESSAGE = "Couldn't start recording."
    }
}

/**
 * Registers the RECORD_AUDIO launcher and releases the recorder on dispose
 * (an in-progress recording is discarded when its composer goes away). When
 * the app goes to the background mid-recording, the clip so far is finished
 * and handed over as a preview ([AudioRecorder.finishForBackground]).
 */
@Composable
fun rememberAudioRecorder(): AudioRecorder {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { PendingPermissionResult() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending.deliver(granted)
    }
    val recorder = remember {
        val appContext = context.applicationContext
        AudioRecorder(
            engine = MediaRecorderEngine(appContext),
            permission = object : MicPermission {
                override fun isGranted(): Boolean = isMicPermissionGranted(appContext)

                override fun request(onResult: (Boolean) -> Unit) {
                    pending.callback = onResult
                    try {
                        launcher.launch(Manifest.permission.RECORD_AUDIO)
                    } catch (_: IllegalStateException) {
                        // No activity-result registry attached (shouldn't happen inside an Activity).
                        pending.deliver(false)
                    }
                }
            },
            scope = scope,
            newFile = { File(appContext.cacheDir, "${UUID.randomUUID()}.m4a") },
        )
    }
    DisposableEffect(recorder) {
        onDispose { recorder.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { recorder.finishForBackground() }
    return recorder
}

internal fun isMicPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private class PendingPermissionResult {
    var callback: ((Boolean) -> Unit)? = null

    fun deliver(granted: Boolean) {
        val current = callback
        callback = null
        current?.invoke(granted)
    }
}

/** Mic permission check + request, abstracted so the recorder's state machine runs in JVM tests. */
internal interface MicPermission {
    fun isGranted(): Boolean
    fun request(onResult: (Boolean) -> Unit)
}

/** Why recording couldn't start, mapped to the iOS failure copy. */
internal class RecorderSetupException(val userMessage: String) : Exception(userMessage)

/** The platform recorder behind [AudioRecorder] (MediaRecorder in the app, a fake in tests). */
internal interface RecordingEngine {
    /** Starts recording into [file]; throws [RecorderSetupException]. [onError] reports a failure mid-recording. */
    fun start(file: File, onError: () -> Unit)

    /** Stops the current recording; true when [file] holds a usable clip. No-op (false) when idle. */
    fun stop(): Boolean

    fun release()
}

/** MediaRecorder → MPEG_4 / AAC, 44.1 kHz, mono (iOS kAudioFormatMPEG4AAC / 44_100 / 1 channel / medium quality). */
internal class MediaRecorderEngine(private val context: Context) : RecordingEngine {
    private var recorder: MediaRecorder? = null

    override fun start(file: File, onError: () -> Unit) {
        release()
        val recorder = newRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        } catch (_: RuntimeException) {
            // iOS: AVAudioSession setCategory/setActive failed.
            recorder.release()
            throw RecorderSetupException(AudioRecorder.MIC_UNAVAILABLE_MESSAGE)
        }
        try {
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(SAMPLE_RATE_HZ)
            recorder.setAudioChannels(1)
            recorder.setAudioEncodingBitRate(BIT_RATE)
            recorder.setOutputFile(file.absolutePath)
            recorder.setOnErrorListener { _, _, _ -> onError() }
            recorder.prepare()
            recorder.start()
        } catch (_: Exception) {
            // iOS: AVAudioRecorder(url:settings:) threw.
            recorder.release()
            throw RecorderSetupException(AudioRecorder.START_FAILED_MESSAGE)
        }
        this.recorder = recorder
    }

    override fun stop(): Boolean {
        val recorder = recorder ?: return false
        this.recorder = null
        return try {
            recorder.stop()
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            recorder.release()
        }
    }

    override fun release() {
        recorder?.release()
        recorder = null
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private companion object {
        const val SAMPLE_RATE_HZ = 44_100
        const val BIT_RATE = 64_000
    }
}
