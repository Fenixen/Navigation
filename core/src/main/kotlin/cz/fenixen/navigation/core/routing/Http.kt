package cz.fenixen.navigation.core.routing

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Body + status of a finished HTTP call. */
internal data class HttpResult(val code: Int, val body: String)

/**
 * Suspending OkHttp call. Cancelling the coroutine cancels the HTTP call as well,
 * so e.g. typing in the search box aborts the previous in-flight request.
 */
internal suspend fun OkHttpClient.awaitString(request: Request): HttpResult =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use { HttpResult(it.code, it.body.string()) }
                cont.resume(result)
            }
        })
    }
