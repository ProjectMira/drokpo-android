package app.drokpo.android.features.auth

import android.app.Activity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.drokpo.android.core.PhoneResendToken
import app.drokpo.android.core.PhoneVerification
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneSignInModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val activity = Activity()

    /** Records every call; answers come from the swappable lambdas. */
    private class FakePhoneAuth {
        val sends = mutableListOf<Pair<String, PhoneResendToken?>>()
        val signIns = mutableListOf<Pair<String, String>>()
        var nextVerificationId = 0
        var send: suspend (String) -> PhoneVerification = {
            PhoneVerification.CodeSent("vid-${++nextVerificationId}", resendToken = null)
        }
        var signIn: suspend (String, String) -> Unit = { _, _ -> }

        /** Stands in for FirebaseAuth's auth-state listener. */
        val signedIn = MutableStateFlow(false)

        fun model() = PhoneSignInModel(
            startVerification = { _, e164, token ->
                sends += e164 to token
                send(e164)
            },
            signInWithPhone = { id, code ->
                signIns += id to code
                signIn(id, code)
            },
            signedIn = signedIn,
        )
    }

    /** Firebase's token has a package-private constructor; it's only ever handed back. */
    private fun resendToken(): PhoneResendToken =
        PhoneResendToken::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    // region Number step rules

    @Test
    fun startsOnNumberStepWithIndia() {
        val state = PhoneSignInState()
        assertEquals(PhoneSignInStep.EnterNumber, state.step)
        assertEquals("India", state.countryCode.name)
        assertEquals("+91", state.dialCode)
        assertEquals("+91", state.countryChipLabel)
        assertFalse(state.canContinue)
    }

    @Test
    fun countryMenuMatchesIos() {
        assertEquals(
            listOf(
                "India (+91)", "Nepal (+977)", "Bhutan (+975)", "US / Canada (+1)", "Switzerland (+41)",
                "France (+33)", "Germany (+49)", "United Kingdom (+44)", "Australia (+61)",
            ),
            countryCodes.map { it.menuLabel },
        )
    }

    @Test
    fun continueNeedsSixDigitsAndAPlusDialCode() {
        val base = PhoneSignInState()
        assertFalse(base.copy(phoneNumber = "98765").canContinue)
        // Only digits count: separators don't make a short number long enough.
        assertFalse(base.copy(phoneNumber = "98-76-5").canContinue)
        assertTrue(base.copy(phoneNumber = "987654").canContinue)
        assertFalse(base.copy(phoneNumber = "987654", isWorking = true).canContinue)

        val other = base.selectingOtherCountry().copy(phoneNumber = "612345678")
        assertEquals("Other", other.countryChipLabel)
        assertFalse(other.copy(customDialCode = "").canContinue)
        assertFalse(other.copy(customDialCode = "+").canContinue)
        assertFalse(other.copy(customDialCode = "31").canContinue)
        assertTrue(other.copy(customDialCode = "+31").canContinue)
    }

    @Test
    fun e164IsDialCodePlusDigitsOnly() {
        assertEquals("+919876543210", PhoneSignInState(phoneNumber = "98765 43210").e164)
        assertEquals("+9779812345678", PhoneSignInState(countryCode = countryCodes[1], phoneNumber = "(981) 234-5678").e164)
        val custom = PhoneSignInState(useCustomCode = true, customDialCode = "+31", phoneNumber = "6 1234 5678")
        assertEquals("+31612345678", custom.e164)
    }

    @Test
    fun pickingACountryLeavesOther() {
        val model = FakePhoneAuth().model()
        model.selectOtherCountry()
        model.updateCustomDialCode("+31")
        assertEquals("+31", model.state.value.dialCode)
        model.selectCountry(countryCodes[3])
        assertFalse(model.state.value.useCustomCode)
        assertEquals("+1", model.state.value.dialCode)
        assertEquals("+1", model.state.value.countryChipLabel)
    }

    // endregion

    // region Sending the code

    @Test
    fun sendCodeMovesToCodeStepAndCountsDown() = runTest(main.dispatcher) {
        val auth = FakePhoneAuth()
        val model = auth.model()
        model.updatePhoneNumber("98765 43210")
        model.updateCode("12") // ignored on the number step
        model.sendCode(activity)
        assertTrue(model.state.value.isWorking)
        assertFalse(model.state.value.canContinue)
        runCurrent()

        val state = model.state.value
        assertEquals(listOf("+919876543210" to null), auth.sends)
        assertEquals(PhoneSignInStep.EnterCode("vid-1"), state.step)
        assertEquals("", state.code)
        assertFalse(state.isWorking)
        assertEquals(30, state.resendCooldown)
        assertEquals("Resend in 30s", state.resendLabel)
        assertFalse(state.canResend)

        advanceTimeBy(1_001)
        assertEquals("Resend in 29s", model.state.value.resendLabel)
        advanceTimeBy(29_000)
        assertEquals(0, model.state.value.resendCooldown)
        assertEquals("Resend code", model.state.value.resendLabel)
        assertTrue(model.state.value.canResend)
    }

    @Test
    fun resendRestartsTheCooldownAndClearsTheCode() = runTest(main.dispatcher) {
        val token = resendToken()
        val auth = FakePhoneAuth().apply {
            send = { PhoneVerification.CodeSent("vid-${++nextVerificationId}", resendToken = token) }
        }
        val model = auth.model()
        model.updatePhoneNumber("9876543210")
        model.sendCode(activity)
        runCurrent()
        model.updateCode("12")
        advanceTimeBy(30_001)
        assertTrue(model.state.value.canResend)

        model.sendCode(activity)
        runCurrent()
        assertEquals(2, auth.sends.size)
        assertEquals("+919876543210", auth.sends[1].first)
        // The first send has no token; "Resend code" hands back the one CodeSent returned.
        assertNull(auth.sends[0].second)
        assertSame(token, auth.sends[1].second)
        assertEquals(PhoneSignInStep.EnterCode("vid-2"), model.state.value.step)
        assertEquals("", model.state.value.code)
        assertEquals(30, model.state.value.resendCooldown)
        advanceTimeBy(10_001)
        assertEquals(20, model.state.value.resendCooldown)
    }

    @Test
    fun aSecondTapWhileWorkingIsIgnored() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<PhoneVerification>()
        val auth = FakePhoneAuth().apply { send = { gate.await() } }
        val model = auth.model()
        model.updatePhoneNumber("9876543210")
        model.sendCode(activity)
        model.sendCode(activity)
        runCurrent()
        assertEquals(1, auth.sends.size)
        gate.complete(PhoneVerification.CodeSent("vid", null))
        advanceUntilIdle()
        assertFalse(model.state.value.isWorking)
    }

    @Test
    fun sendFailureShowsTheFriendlyMessageAndStaysOnNumberStep() = runTest(main.dispatcher) {
        val auth = FakePhoneAuth().apply {
            send = { throw FirebaseAuthInvalidCredentialsException("ERROR_INVALID_PHONE_NUMBER", "The format of the phone number provided is incorrect.") }
        }
        val model = auth.model()
        model.updatePhoneNumber("123456")
        model.sendCode(activity)
        advanceUntilIdle()
        val state = model.state.value
        assertEquals("That doesn't look like a valid phone number.", state.errorMessage)
        assertEquals(PhoneSignInStep.EnterNumber, state.step)
        assertFalse(state.isWorking)
        assertEquals(0, state.resendCooldown)

        model.dismissError()
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun missingActivityIsReportedNotCrashed() = runTest(main.dispatcher) {
        val auth = FakePhoneAuth()
        val model = auth.model()
        model.updatePhoneNumber("9876543210")
        model.sendCode(activity = null)
        advanceUntilIdle()
        assertTrue(auth.sends.isEmpty())
        assertEquals("Couldn't present the sign-in screen.", model.state.value.errorMessage)
    }

    @Test
    fun autoVerificationFinishesWithoutTheCodeStep() = runTest(main.dispatcher) {
        val auth = FakePhoneAuth().apply { send = { PhoneVerification.AutoVerified } }
        val model = auth.model()
        model.updatePhoneNumber("9876543210")
        model.sendCode(activity)
        advanceUntilIdle()
        assertTrue(model.state.value.finished)
        assertEquals(PhoneSignInStep.EnterNumber, model.state.value.step)
        assertTrue(auth.signIns.isEmpty())
    }

    @Test
    fun aLaterAutoSignInFinishesTheCodeStepAndBlocksVerifying() = runTest(main.dispatcher) {
        val auth = FakePhoneAuth()
        val model = auth.model()
        model.updatePhoneNumber("9876543210")
        model.sendCode(activity)
        runCurrent()
        assertEquals(PhoneSignInStep.EnterCode("vid-1"), model.state.value.step)
        assertFalse(model.state.value.finished)

        // SMS auto-retrieval signs in on the app scope after CodeSent.
        auth.signedIn.value = true
        runCurrent()
        assertTrue(model.state.value.finished)

        // The code the user was typing must not hit the consumed verification.
        model.updateCode("482913")
        model.sendCode(activity)
        advanceUntilIdle()
        assertTrue(auth.signIns.isEmpty())
        assertEquals(1, auth.sends.size)
        assertNull(model.state.value.errorMessage)
        assertFalse(model.state.value.isWorking)
    }

    @Test
    fun aVerifyFailingAfterTheAutoSignInShowsNoAlert() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val auth = FakePhoneAuth().apply {
            signIn = { _, _ ->
                gate.await()
                throw FirebaseAuthInvalidCredentialsException("ERROR_SESSION_EXPIRED", "The sms code has expired.")
            }
        }
        val model = auth.model()
        model.updatePhoneNumber("9876543210")
        model.sendCode(activity)
        runCurrent()
        model.updateCode("482913")
        runCurrent()
        assertTrue(model.state.value.isWorking)

        auth.signedIn.value = true
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.finished)
        assertNull(model.state.value.errorMessage)
        assertFalse(model.state.value.isWorking)
    }

    // endregion

    // region Process death

    @Test
    fun snapshotRestoresTheCodeStepWithTheResendTokenAndNoCooldown() = runTest(main.dispatcher) {
        val token = resendToken()
        val auth = FakePhoneAuth().apply { send = { PhoneVerification.CodeSent("vid-9", resendToken = token) } }
        val before = auth.model()
        before.selectCountry(countryCodes[1])
        before.updatePhoneNumber("981 234 5678")
        before.sendCode(activity)
        runCurrent()
        before.updateCode("48")
        val snapshot = before.snapshot()
        assertEquals(
            PhoneSignInSnapshot("+977", false, "", "981 234 5678", verificationId = "vid-9", resendToken = token),
            snapshot,
        )

        // A new process: fresh model, seeded once from the saved snapshot.
        val after = auth.model()
        after.restore(snapshot)
        val state = after.state.value
        assertEquals(PhoneSignInStep.EnterCode("vid-9"), state.step)
        assertEquals("+9779812345678", state.e164)
        assertEquals("", state.code)
        assertEquals(0, state.resendCooldown)
        assertTrue(state.canResend)

        // Typing the SMS that arrived meanwhile verifies against the restored id.
        after.updateCode("123456")
        runCurrent()
        assertEquals(listOf("vid-9" to "123456"), auth.signIns)
        assertTrue(after.state.value.finished)
    }

    @Test
    fun aRestoredCodeStepResendsWithTheSavedToken() = runTest(main.dispatcher) {
        val token = resendToken()
        val auth = FakePhoneAuth()
        val model = auth.model()
        model.restore(PhoneSignInSnapshot("+91", false, "", "9876543210", verificationId = "vid-9", resendToken = token))
        model.sendCode(activity)
        runCurrent()
        assertEquals(1, auth.sends.size)
        assertSame(token, auth.sends[0].second)
        assertEquals(PhoneSignInStep.EnterCode("vid-1"), model.state.value.step)
        assertEquals(30, model.state.value.resendCooldown)
    }

    @Test
    fun snapshotOnTheNumberStepKeepsTheCustomDialCode() {
        val auth = FakePhoneAuth()
        val before = auth.model()
        before.selectOtherCountry()
        before.updateCustomDialCode("+31")
        before.updatePhoneNumber("612345678")
        val snapshot = before.snapshot()
        assertNull(snapshot.verificationId)
        assertNull(snapshot.resendToken)

        val after = auth.model()
        after.restore(snapshot)
        val state = after.state.value
        assertEquals(PhoneSignInStep.EnterNumber, state.step)
        assertEquals("Other", state.countryChipLabel)
        assertEquals("+31612345678", state.e164)
        assertTrue(state.canContinue)

        // An unknown country id falls back to India.
        after.restore(snapshot.copy(countryDialCode = "+999", useCustomCode = false))
        assertEquals("+91", after.state.value.dialCode)
    }

    // endregion

    // region Code entry

    @Test
    fun codeKeepsDigitsOnlyAndAtMostSix() {
        assertEquals("123456", sanitizeOtp("12 34-56789"))
        assertEquals("", sanitizeOtp("abc"))
        assertEquals("042", sanitizeOtp("0a4b2"))
    }

    @Test
    fun sixthDigitVerifiesOnceAndFinishes() = runTest(main.dispatcher) {
        val auth = FakePhoneAuth()
        val model = auth.model()
        model.updatePhoneNumber("9876543210")
        model.sendCode(activity)
        runCurrent()

        model.updateCode("48291")
        runCurrent()
        assertTrue(auth.signIns.isEmpty())
        // A paste with separators still lands as six digits and submits once.
        model.updateCode("482 913")
        model.updateCode("482913")
        assertEquals("482913", model.state.value.code)
        assertTrue(model.state.value.isWorking)
        advanceUntilIdle()
        assertEquals(listOf("vid-1" to "482913"), auth.signIns)
        assertTrue(model.state.value.finished)
        assertFalse(model.state.value.isWorking)
    }

    @Test
    fun wrongCodeShowsMessageAndCanBeRetyped() = runTest(main.dispatcher) {
        val auth = FakePhoneAuth().apply {
            signIn = { _, code ->
                if (code != "123456") {
                    throw FirebaseAuthInvalidCredentialsException("ERROR_INVALID_VERIFICATION_CODE", "The verification code is invalid.")
                }
            }
        }
        val model = auth.model()
        model.updatePhoneNumber("9876543210")
        model.sendCode(activity)
        runCurrent()

        model.updateCode("111111")
        advanceTimeBy(1)
        runCurrent()
        assertEquals("That code isn't right. Check and try again.", model.state.value.errorMessage)
        assertFalse(model.state.value.finished)

        model.dismissError()
        model.updateCode("11111")
        model.updateCode("123456")
        runCurrent()
        assertEquals(listOf("vid-1" to "111111", "vid-1" to "123456"), auth.signIns)
        assertTrue(model.state.value.finished)
    }

    @Test
    fun dismissingCancelsTheCooldown() = runTest(main.dispatcher) {
        val auth = FakePhoneAuth()
        val store = ViewModelStore()
        val model = ViewModelProvider.create(store, viewModelFactory { initializer { auth.model() } })[PhoneSignInModel::class]
        model.updatePhoneNumber("9876543210")
        model.sendCode(activity)
        runCurrent()
        advanceTimeBy(5_001)
        assertEquals(25, model.state.value.resendCooldown)

        store.clear() // the FullScreenCover's presentation scope going away
        advanceTimeBy(10_000)
        assertEquals(25, model.state.value.resendCooldown)
    }

    // endregion
}
