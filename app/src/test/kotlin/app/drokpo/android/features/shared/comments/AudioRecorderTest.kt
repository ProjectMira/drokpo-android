package app.drokpo.android.features.shared.comments

import app.drokpo.android.features.shared.audio.AudioRecorder
import app.drokpo.android.features.shared.audio.MicPermission
import app.drokpo.android.features.shared.audio.RecordedClip
import app.drokpo.android.features.shared.audio.RecorderSetupException
import app.drokpo.android.features.shared.audio.RecorderState
import app.drokpo.android.features.shared.audio.RecordingEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class AudioRecorderTest {
    private val dir: File = Files.createTempDirectory("recorder-test").toFile()

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private class FakeEngine : RecordingEngine {
        var startFailure: String? = null
        var usableOnStop = true
        var started = 0
        var stopped = 0
        var released = false
        var onError: (() -> Unit)? = null

        override fun start(file: File, onError: () -> Unit) {
            startFailure?.let { throw RecorderSetupException(it) }
            started++
            file.writeText("aac")
            this.onError = onError
        }

        override fun stop(): Boolean {
            stopped++
            return usableOnStop
        }

        override fun release() {
            released = true
        }
    }

    private class FakePermission(var granted: Boolean) : MicPermission {
        val pending = mutableListOf<(Boolean) -> Unit>()
        var autoAnswer: Boolean? = null

        override fun isGranted() = granted

        override fun request(onResult: (Boolean) -> Unit) {
            val answer = autoAnswer
            if (answer != null) onResult(answer) else pending += onResult
        }
    }

    private inner class Harness(scope: TestScope, granted: Boolean = true) {
        val engine = FakeEngine()
        val permission = FakePermission(granted)
        var fileCount = 0
        /** Wall-clock drift on top of virtual time — a tick that fires late. */
        var clockSkew = 0L
        val recorder = AudioRecorder(
            engine = engine,
            permission = permission,
            scope = scope.backgroundScope,
            newFile = { File(dir, "clip-${++fileCount}.m4a") },
            clock = { scope.testScheduler.currentTime + clockSkew },
        )
        val state: RecorderState get() = recorder.state.value
    }

    @Test fun deniedPermissionFailsWithTheSettingsHintAndCanBeDismissed() = runTest {
        val h = Harness(this, granted = false)
        h.permission.autoAnswer = false
        h.recorder.start(60) {}
        assertEquals(RecorderState.Failed("Microphone access is off. You can turn it on in Settings."), h.state)
        assertEquals(0, h.engine.started)
        // A denied mic must never lock the user out of typing.
        h.recorder.dismissFailure()
        assertEquals(RecorderState.Idle, h.state)
    }

    @Test fun grantingThePermissionStartsRecording() = runTest {
        val h = Harness(this, granted = false)
        h.recorder.start(60) {}
        assertEquals("Waiting on the dialog", RecorderState.Idle, h.state)
        h.permission.pending.single().invoke(true)
        assertEquals(RecorderState.Recording(0), h.state)
        assertEquals(1, h.engine.started)
    }

    @Test fun timerTicksEverySecond() = runTest {
        val h = Harness(this)
        h.recorder.start(60) {}
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(RecorderState.Recording(1), h.state)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(RecorderState.Recording(3), h.state)
    }

    @Test fun autoStopsAtTheLimitAndHandsOverTheClip() = runTest {
        val h = Harness(this)
        var clip: RecordedClip? = null
        h.recorder.start(5) { clip = it }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(RecorderState.Idle, h.state)
        val result = clip
        assertNotNull(result)
        assertEquals(5, result!!.seconds)
        assertTrue(result.file.exists())
        assertEquals(1, h.engine.stopped)
    }

    @Test fun stopReturnsAtLeastOneSecond() = runTest {
        val h = Harness(this)
        h.recorder.start(60) {}
        val clip = h.recorder.stop()
        assertEquals(1, clip?.seconds)
        assertTrue(clip!!.file.exists())
        assertEquals(RecorderState.Idle, h.state)
    }

    @Test fun stopRoundsTheElapsedTimeAndNeverExceedsTheLimit() = runTest {
        val h = Harness(this)
        h.recorder.start(60) {}
        advanceTimeBy(7_600)
        assertEquals(8, h.recorder.stop()?.seconds)

        val capped = Harness(this)
        capped.recorder.start(3) {}
        // A late tick must not hand the backend a 4 s clip for a 3 s limit.
        capped.clockSkew = 3_600
        assertEquals(3, capped.recorder.stop()?.seconds)
    }

    @Test fun anUnusableRecordingIsDiscarded() = runTest {
        val h = Harness(this)
        h.engine.usableOnStop = false
        h.recorder.start(60) {}
        assertNull(h.recorder.stop())
        assertFalse(File(dir, "clip-1.m4a").exists())
    }

    @Test fun cancelDeletesTheFile() = runTest {
        val h = Harness(this)
        h.recorder.start(60) {}
        val file = File(dir, "clip-1.m4a")
        assertTrue(file.exists())
        h.recorder.cancel()
        assertFalse(file.exists())
        assertEquals(RecorderState.Idle, h.state)
        assertNull("Nothing left to stop", h.recorder.stop())
    }

    @Test fun setupFailuresUseTheIosCopy() = runTest {
        val h = Harness(this)
        h.engine.startFailure = AudioRecorder.MIC_UNAVAILABLE_MESSAGE
        h.recorder.start(60) {}
        assertEquals(RecorderState.Failed("Couldn't access the microphone."), h.state)
        assertFalse(File(dir, "clip-1.m4a").exists())

        h.recorder.dismissFailure()
        h.engine.startFailure = AudioRecorder.START_FAILED_MESSAGE
        h.recorder.start(60) {}
        assertEquals(RecorderState.Failed("Couldn't start recording."), h.state)
    }

    @Test fun anEngineErrorMidRecordingFailsAndCleansUp() = runTest {
        val h = Harness(this)
        h.recorder.start(60) {}
        h.engine.onError!!.invoke()
        assertEquals(RecorderState.Failed("Couldn't start recording."), h.state)
        assertFalse(File(dir, "clip-1.m4a").exists())
    }

    @Test fun startWhileRecordingIsIgnored() = runTest {
        val h = Harness(this)
        h.recorder.start(60) {}
        h.recorder.start(60) {}
        assertEquals(1, h.engine.started)
    }

    @Test fun aLateGrantAfterCancelOrReleaseNeverStartsRecording() = runTest {
        val h = Harness(this, granted = false)
        h.recorder.start(60) {}
        h.recorder.cancel()
        h.permission.pending.single().invoke(true)
        assertEquals(0, h.engine.started)

        h.recorder.start(60) {}
        h.recorder.release()
        h.permission.pending.last().invoke(true)
        assertEquals(0, h.engine.started)
        assertTrue(h.engine.released)
        assertEquals(RecorderState.Idle, h.state)
    }

    @Test fun releaseDiscardsAnInProgressRecording() = runTest {
        val h = Harness(this)
        h.recorder.start(60) {}
        h.recorder.release()
        assertFalse(File(dir, "clip-1.m4a").exists())
        h.recorder.start(60) {}
        assertEquals("A released recorder stays dead", 1, h.engine.started)
    }

    @Test fun goingToTheBackgroundFinishesTheRecordingAsAPreview() = runTest {
        val h = Harness(this)
        var clip: RecordedClip? = null
        h.recorder.start(60) { clip = it }
        advanceTimeBy(4_000)
        runCurrent()
        h.recorder.finishForBackground()
        assertEquals(RecorderState.Idle, h.state)
        assertEquals("What was captured before the app left", 4, clip?.seconds)
        assertTrue(clip!!.file.exists())
        assertEquals(1, h.engine.stopped)

        // The timer is gone: no tick, no second hand-over at the limit.
        clip = null
        advanceTimeBy(120_000)
        runCurrent()
        assertNull(clip)
        assertEquals(RecorderState.Idle, h.state)
    }

    @Test fun goingToTheBackgroundWithNothingUsableJustGoesIdle() = runTest {
        val h = Harness(this)
        h.engine.usableOnStop = false
        var handedOver = false
        h.recorder.start(60) { handedOver = true }
        h.recorder.finishForBackground()
        assertFalse(handedOver)
        assertEquals(RecorderState.Idle, h.state)
        assertFalse(File(dir, "clip-1.m4a").exists())
    }

    @Test fun goingToTheBackgroundWhileNotRecordingChangesNothing() = runTest {
        val h = Harness(this, granted = false)
        h.permission.autoAnswer = false
        var handedOver = false
        h.recorder.start(60) { handedOver = true }
        h.recorder.finishForBackground()
        assertEquals("A failure stays on screen", RecorderState.Failed(AudioRecorder.MIC_OFF_MESSAGE), h.state)
        assertFalse(handedOver)
        assertEquals(0, h.engine.stopped)

        val idle = Harness(this)
        idle.recorder.finishForBackground()
        assertEquals(RecorderState.Idle, idle.state)
        assertEquals(0, idle.engine.stopped)
    }

    @Test fun stopWithNothingRecordingClearsAFailure() = runTest {
        val h = Harness(this, granted = false)
        h.permission.autoAnswer = false
        h.recorder.start(60) {}
        assertNull(h.recorder.stop())
        assertEquals(RecorderState.Idle, h.state)
    }
}
