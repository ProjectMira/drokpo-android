package app.drokpo.android.features.shared.comments

import app.drokpo.android.features.shared.audio.PlaybackController
import app.drokpo.android.features.shared.audio.PlaybackEngine
import app.drokpo.android.features.shared.audio.VoiceClipCache
import app.drokpo.android.features.shared.audio.audioDurationLabel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlaybackTest {
    private class FakeEngine : PlaybackEngine {
        val started = mutableListOf<String>()
        var stops = 0
        var onEnded: (() -> Unit)? = null
        var onError: (() -> Unit)? = null
        var progress: Float? = null

        override fun start(source: String, onEnded: () -> Unit, onError: () -> Unit) {
            started += source
            this.onEnded = onEnded
            this.onError = onError
        }

        override fun stop() {
            stops++
        }

        override fun currentProgress(): Float? = progress
    }

    private class Harness(scope: TestScope) {
        val engine = FakeEngine()
        var engineCreations = 0
        val resolved = mutableListOf<String>()
        var resolveGate: CompletableDeferred<Unit>? = null
        var resolveFailure: Exception? = null
        val controller = PlaybackController(
            scope = scope.backgroundScope,
            engineFactory = {
                engineCreations++
                engine
            },
            resolve = { url ->
                resolved += url
                resolveGate?.await()
                resolveFailure?.let { throw it }
                "local:$url"
            },
        )
    }

    @Test fun playsAClipAndTappingItAgainStopsIt() = runTest {
        val h = Harness(this)
        h.controller.play("a", "https://x/a.m4a")
        runCurrent()
        assertEquals("a", h.controller.playingId.value)
        assertEquals(listOf("local:https://x/a.m4a"), h.engine.started)

        h.controller.play("a", "https://x/a.m4a")
        assertNull(h.controller.playingId.value)
        assertEquals(0f, h.controller.progress.value)
        assertTrue(h.engine.stops > 0)
    }

    @Test fun startingAnotherClipStopsTheFirst() = runTest {
        val h = Harness(this)
        h.controller.play("a", "https://x/a.m4a")
        runCurrent()
        val stopsBefore = h.engine.stops
        h.controller.play("b", "https://x/b.m4a")
        runCurrent()
        assertEquals("b", h.controller.playingId.value)
        assertTrue(h.engine.stops > stopsBefore)
        assertEquals("One player for the whole app", 1, h.engineCreations)
    }

    @Test fun finishingOrFailingStops() = runTest {
        val h = Harness(this)
        h.controller.play("a", "file:///draft.m4a")
        runCurrent()
        h.engine.onEnded!!.invoke()
        assertNull(h.controller.playingId.value)

        h.controller.play("b", "file:///b.m4a")
        runCurrent()
        h.engine.onError!!.invoke()
        assertNull(h.controller.playingId.value)
    }

    @Test fun aStaleCallbackFromThePreviousClipIsIgnored() = runTest {
        val h = Harness(this)
        h.controller.play("a", "u-a")
        runCurrent()
        val firstEnded = h.engine.onEnded!!
        h.controller.play("b", "u-b")
        runCurrent()
        firstEnded()
        assertEquals("b", h.controller.playingId.value)
    }

    @Test fun downloadFailureIsSilent() = runTest {
        val h = Harness(this)
        h.resolveFailure = IOException("offline")
        h.controller.play("a", "https://x/a.m4a")
        runCurrent()
        assertNull(h.controller.playingId.value)
        assertTrue(h.engine.started.isEmpty())
        // …and the clip can be tried again.
        h.resolveFailure = null
        h.controller.play("a", "https://x/a.m4a")
        runCurrent()
        assertEquals("a", h.controller.playingId.value)
    }

    @Test fun tappingAnotherClipCancelsAPendingDownload() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.resolveGate = gate
        h.controller.play("a", "u-a")
        runCurrent()
        h.controller.play("b", "u-b")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("b", h.controller.playingId.value)
        assertEquals("Only the second clip ever starts", listOf("local:u-b"), h.engine.started)
    }

    @Test fun aRepeatTapWhileDownloadingDoesNotRestart() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.resolveGate = gate
        h.controller.play("a", "u-a")
        runCurrent()
        h.controller.play("a", "u-a")
        runCurrent()
        assertEquals(listOf("u-a"), h.resolved)
        gate.complete(Unit)
        runCurrent()
        assertEquals("a", h.controller.playingId.value)
    }

    @Test fun progressUpdatesEveryHundredMillisWhilePlaying() = runTest {
        val h = Harness(this)
        h.controller.play("a", "u-a")
        runCurrent()
        h.engine.progress = 0.25f
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0.25f, h.controller.progress.value)
        h.engine.progress = null // paused/buffering: keep the last value
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0.25f, h.controller.progress.value)
        h.controller.stop()
        assertEquals(0f, h.controller.progress.value)
    }

    @Test fun durationLabelShowsRemainingTimeWhilePlaying() {
        assertEquals("0:14", audioDurationLabel(14, isPlaying = false, progress = 0f))
        assertEquals("1:15", audioDurationLabel(75, isPlaying = false, progress = 0.5f))
        assertEquals("0:08", audioDurationLabel(14, isPlaying = true, progress = 0.4f))
        assertEquals("0:00", audioDurationLabel(14, isPlaying = true, progress = 1f))
        assertEquals("2:05", audioDurationLabel(125, isPlaying = true, progress = 0f))
        assertEquals("0:00", audioDurationLabel(-3, isPlaying = false, progress = 0f))
    }

    @Test fun cacheKeysAreStableAndDistinct() {
        val a = VoiceClipCache.cacheKey("https://firebasestorage.googleapis.com/a.m4a?token=1")
        assertEquals(a, VoiceClipCache.cacheKey("https://firebasestorage.googleapis.com/a.m4a?token=1"))
        assertNotEquals(a, VoiceClipCache.cacheKey("https://firebasestorage.googleapis.com/a.m4a?token=2"))
        assertEquals(32, a.length)
        val file = VoiceClipCache.cachedFile(File("/cache"), "https://x/a")
        assertEquals("voice-cache", file.parentFile?.name)
        assertTrue(file.name.endsWith(".m4a"))
    }
}
