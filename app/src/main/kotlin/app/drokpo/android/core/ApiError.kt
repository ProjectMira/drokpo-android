package app.drokpo.android.core

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Port of the Swift `APIError` (a `LocalizedError`). */
sealed class ApiError(message: String) : Exception(message) {
    data object NotAuthenticated : ApiError("You need to sign in again.")

    data class Http(val status: Int, override val message: String) : ApiError(message)

    data object InvalidResponse : ApiError("Unexpected response from the server.")

    /** Swift `errorDescription`. */
    val errorDescription: String
        get() = when (this) {
            NotAuthenticated -> "You need to sign in again."
            is Http -> message.ifEmpty { "Server error ($status)." }
            InvalidResponse -> "Unexpected response from the server."
        }
}

/**
 * What the iOS app shows for `error.localizedDescription`: `ApiError` cases
 * verbatim, the Foundation strings for decoding and URLSession failures, and
 * otherwise the throwable's own message.
 */
fun Throwable.userMessage(): String = when (this) {
    is ApiError -> errorDescription
    // DecodingError.keyNotFound / valueNotFound (NSCoderValueNotFoundError).
    is MissingFieldException -> "The data couldn't be read because it is missing."
    // DecodingError.typeMismatch / dataCorrupted (NSCoderReadCorruptError).
    is SerializationException -> "The data couldn't be read because it isn't in the correct format."
    // Offline on Android usually surfaces as a failed DNS lookup.
    is UnknownHostException -> "The Internet connection appears to be offline."
    is NoRouteToHostException -> "The Internet connection appears to be offline."
    is ConnectException -> "Could not connect to the server."
    is SSLException -> "An SSL error has occurred and a secure connection to the server cannot be made."
    // SocketTimeoutException and OkHttp's call timeout are InterruptedIOExceptions.
    is InterruptedIOException -> "The request timed out."
    is FileNotFoundException -> localizedMessage?.takeIf { it.isNotBlank() } ?: "The file couldn't be opened."
    is IOException -> "The network connection was lost."
    else -> localizedMessage?.takeIf { it.isNotBlank() } ?: "The operation couldn't be completed."
}
