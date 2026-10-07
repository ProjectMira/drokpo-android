package app.drokpo.android.features.auth

import android.app.Activity
import app.drokpo.android.core.AuthServiceError
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class SignInViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val activity = Activity()

    private class Providers {
        val calls = mutableListOf<String>()
        var google: suspend (Activity) -> Unit = {}
        var apple: suspend (Activity) -> Unit = {}

        fun model() = SignInViewModel(
            googleSignIn = { calls += "google"; google(it) },
            appleSignIn = { calls += "apple"; apple(it) },
        )
    }

    @Test
    fun googleSuccessSpinsThenLeavesRoutingToTheSession() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var presentedFrom: Activity? = null
        val providers = Providers().apply {
            google = {
                presentedFrom = it
                gate.await()
            }
        }
        val model = providers.model()
        model.signInWithGoogle(activity)
        runCurrent()
        assertTrue(model.state.value.isSigningIn)
        assertSame(activity, presentedFrom)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SignInUiState(), model.state.value)
        assertEquals(listOf("google"), providers.calls)
    }

    @Test
    fun appleUsesTheAppleProvider() = runTest(main.dispatcher) {
        val providers = Providers()
        val model = providers.model()
        model.signInWithApple(activity)
        advanceUntilIdle()
        assertEquals(listOf("apple"), providers.calls)
    }

    @Test
    fun failureShowsSignInFailedMessage() = runTest(main.dispatcher) {
        val providers = Providers().apply { google = { throw AuthServiceError.NoGoogleAccount() } }
        val model = providers.model()
        model.signInWithGoogle(activity)
        advanceUntilIdle()
        assertEquals(
            "No Google account is available on this device. Add one in Settings, or continue with phone.",
            model.state.value.errorMessage,
        )
        assertFalse(model.state.value.isSigningIn)

        model.dismissError()
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun networkFailureUsesTheIosWording() = runTest(main.dispatcher) {
        val providers = Providers().apply { google = { throw UnknownHostException("www.googleapis.com") } }
        val model = providers.model()
        model.signInWithGoogle(activity)
        advanceUntilIdle()
        assertEquals("The Internet connection appears to be offline.", model.state.value.errorMessage)
    }

    @Test
    fun cancellingTheProviderUiIsSilent() = runTest(main.dispatcher) {
        val providers = Providers().apply { apple = { throw AuthServiceError.Cancelled() } }
        val model = providers.model()
        model.signInWithApple(activity)
        advanceUntilIdle()
        assertNull(model.state.value.errorMessage)
        assertFalse(model.state.value.isSigningIn)
    }

    @Test
    fun noActivityMeansNoPresenter() = runTest(main.dispatcher) {
        val providers = Providers()
        val model = providers.model()
        model.signInWithGoogle(activity = null)
        advanceUntilIdle()
        assertTrue(providers.calls.isEmpty())
        assertEquals("Couldn't present the sign-in screen.", model.state.value.errorMessage)
    }

    @Test
    fun everythingIsDisabledWhileSigningIn() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val providers = Providers().apply { google = { gate.await() } }
        val model = providers.model()
        model.signInWithGoogle(activity)
        model.signInWithGoogle(activity)
        model.signInWithApple(activity)
        model.showPhoneSignIn()
        runCurrent()
        assertEquals(listOf("google"), providers.calls)
        assertFalse(model.state.value.showPhoneSignIn)

        gate.complete(Unit)
        advanceUntilIdle()
        model.showPhoneSignIn()
        assertTrue(model.state.value.showPhoneSignIn)
        model.dismissPhoneSignIn()
        assertFalse(model.state.value.showPhoneSignIn)
    }

    @Test
    fun aRestoredOpenPhoneCoverStartsOpen() {
        // SignInScreen seeds this from rememberSaveable after process death.
        val model = SignInViewModel(googleSignIn = {}, appleSignIn = {}, initialShowPhoneSignIn = true)
        assertEquals(SignInUiState(showPhoneSignIn = true), model.state.value)
        model.dismissPhoneSignIn()
        assertFalse(model.state.value.showPhoneSignIn)
        assertFalse(SignInViewModel(googleSignIn = {}, appleSignIn = {}).state.value.showPhoneSignIn)
    }
}
