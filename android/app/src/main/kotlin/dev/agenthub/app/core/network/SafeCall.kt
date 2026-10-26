package dev.agenthub.app.core.network

import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

/** The error boundary: HTTP and IO failures become [DataError]; raw bodies and stack traces never escape. */
suspend fun <T> apiCall(block: suspend () -> Response<T>): AppResult<T> = guarded {
    val response = block()
    val body = response.body()
    when {
        response.isSuccessful && body != null -> AppResult.Success(body)
        response.isSuccessful -> AppResult.Failure(DataError.Unknown)
        else -> AppResult.Failure(ApiErrorParser.toError(response.code(), response.errorBody()?.string()))
    }
}

/** For endpoints that return no body (204). */
suspend fun apiUnitCall(block: suspend () -> Response<Unit>): AppResult<Unit> = guarded {
    val response = block()
    if (response.isSuccessful) {
        AppResult.Success(Unit)
    } else {
        AppResult.Failure(ApiErrorParser.toError(response.code(), response.errorBody()?.string()))
    }
}

private inline fun <T> guarded(block: () -> AppResult<T>): AppResult<T> = try {
    block()
} catch (e: IOException) {
    AppResult.Failure(DataError.Network)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppResult.Failure(DataError.Unknown)
}
