package app.drokpo.android.features.auth

import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class PhoneAuthErrorMessageTest {
    private fun authError(code: String) = FirebaseAuthException(code, "Firebase says $code")

    @Test
    fun mapsFirebaseCodesToTheIosCopy() {
        assertEquals(
            "That doesn't look like a valid phone number.",
            phoneAuthErrorMessage(FirebaseAuthInvalidCredentialsException("ERROR_INVALID_PHONE_NUMBER", "bad format")),
        )
        assertEquals("Enter a phone number first.", phoneAuthErrorMessage(authError("ERROR_MISSING_PHONE_NUMBER")))
        assertEquals(
            "That code isn't right. Check and try again.",
            phoneAuthErrorMessage(FirebaseAuthInvalidCredentialsException("ERROR_INVALID_VERIFICATION_CODE", "invalid")),
        )
        assertEquals("This code expired — request a new one.", phoneAuthErrorMessage(authError("ERROR_SESSION_EXPIRED")))
        assertEquals(
            "This code expired — request a new one.",
            phoneAuthErrorMessage(authError("ERROR_INVALID_VERIFICATION_ID")),
        )
    }

    @Test
    fun quotaArrivesAsTooManyRequestsOrAQuotaCode() {
        val expected = "Too many attempts right now — try again in a bit."
        assertEquals(expected, phoneAuthErrorMessage(FirebaseTooManyRequestsException("We have blocked all requests from this device.")))
        assertEquals(expected, phoneAuthErrorMessage(authError("ERROR_QUOTA_EXCEEDED")))
        assertEquals(expected, phoneAuthErrorMessage(authError("ERROR_TOO_MANY_REQUESTS")))
    }

    @Test
    fun anythingElseFallsBackToTheErrorText() {
        assertEquals("Firebase says ERROR_APP_NOT_AUTHORIZED", phoneAuthErrorMessage(authError("ERROR_APP_NOT_AUTHORIZED")))
        assertEquals("The network connection was lost.", phoneAuthErrorMessage(IOException("reset")))
    }
}
