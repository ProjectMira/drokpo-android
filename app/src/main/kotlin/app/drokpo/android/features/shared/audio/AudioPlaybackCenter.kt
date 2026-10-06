package app.drokpo.android.features.shared.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Port of AudioPlaybackCenter: one clip at a time app-wide (Media3 ExoPlayer on AppGraph.app).
 *  (CONTRACT §B.10 — stub: tracks which clip is "playing" but produces no sound.) */
object AudioPlaybackCenter {
    private val _playingId = MutableStateFlow<String?>(null)
    private val _progress = MutableStateFlow(0f)

    val playingId: StateFlow<String?> = _playingId.asStateFlow()
    /** 0..1 of the playing clip, updated ~100 ms. */
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /** Same id as playing → stop (pause gesture); otherwise stop current and play `url`
     *  (https → downloaded once to cacheDir/voice-cache/{hash}.m4a; file:// → played directly). Silent on failure. */
    fun play(id: String, url: String) {
        if (_playingId.value == id) {
            stop()
            return
        }
        _progress.value = 0f
        _playingId.value = id
    }

    fun stop() {
        _playingId.value = null
        _progress.value = 0f
    }
}
