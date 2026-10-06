package app.drokpo.android.features.shared.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class RecordedClip(val file: File, val seconds: Int)

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(val elapsedSeconds: Int) : RecorderState
    data class Failed(val message: String) : RecorderState
}

/** Port of AudioRecorder: MediaRecorder → cacheDir/{uuid}.m4a (MPEG_4 / AAC, 44.1 kHz, mono).
 *  One instance per composer/input bar (rememberAudioRecorder).
 *  (CONTRACT §B.10 — stub: never records; start() reports a failure so callers' failure row shows.) */
class AudioRecorder internal constructor() {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    /** Requests RECORD_AUDIO if needed, then records; auto-stops at maxSeconds and calls onAutoStop. */
    fun start(maxSeconds: Int, onAutoStop: (RecordedClip) -> Unit) {
        _state.value = RecorderState.Failed("Couldn't start recording.")
    }

    /** Stops and returns the clip (seconds = max(1, rounded elapsed)), or null if not recording. */
    fun stop(): RecordedClip? {
        _state.value = RecorderState.Idle
        return null
    }

    /** Stops and deletes the file. */
    fun cancel() {
        _state.value = RecorderState.Idle
    }

    /** Failed → Idle, so a denied mic never locks the user out of typing. */
    fun dismissFailure() {
        if (_state.value is RecorderState.Failed) _state.value = RecorderState.Idle
    }
}

/** Registers the RECORD_AUDIO launcher and releases the recorder on dispose. */
@Composable
fun rememberAudioRecorder(): AudioRecorder = remember { AudioRecorder() }
