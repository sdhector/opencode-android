package ai.opencode.server.live

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.log.logSummary
import ai.opencode.core.strings.localized
import ai.opencode.server.ConnectionFailureDetail
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.R
import ai.opencode.server.ServerLinkClient
import ai.opencode.server.ServerLinkEpoch
import ai.opencode.server.UrlErrorCode
import ai.opencode.server.internal.WireJson
import ai.opencode.server.model.OpenCodeMediaReference
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.ServerContact
import ai.opencode.server.retry.TransportFailure
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Response bytes with the identity of the request that fetched them. */
internal class MeasuredResponse(val data: ByteArray, val requestID: String, val correlation: NetworkRetry.Correlation) {
    val count: Int get() = data.size
}

/** An answered call: status, headers, the body read in full, and the final URL. */
internal class RawResponse(val code: Int, val headers: okhttp3.Headers, val body: ByteArray, val url: HttpUrl)

internal val JSON_MEDIA_TYPE = "application/json".toMediaType()
internal val EMPTY_JSON_OBJECT = "{}".toByteArray()

internal fun locationQuery(directory: String?): List<Pair<String, String>> =
    directory?.let { listOf("location[directory]" to it) } ?: emptyList()

/** A route as the log shows it: IDs stay, a pairing code doesn't. */
/**
 * Advertises `X-OpenCode-Capabilities: experimental-media-uri` (merged into an existing
 * comma-separated list); the server decides whether to answer with media references or inline data.
 * Sent with every request that carries correlation headers.
 */
internal fun applyCapabilities(request: Request.Builder): Request.Builder {
    val capability = OpenCodeMediaReference.CAPABILITY
    val existing = request.build().header(CAPABILITIES_HEADER)?.trim()
    if (existing.isNullOrEmpty()) return request.header(CAPABILITIES_HEADER, capability)
    val values = existing.split(",").map { it.trim() }
    if (capability in values) return request
    return request.header(CAPABILITIES_HEADER, (values + capability).joinToString(", "))
}

internal const val CAPABILITIES_HEADER = "X-OpenCode-Capabilities"

internal fun loggablePath(path: String): String = if (path.startsWith("/auth/")) "/auth/…" else path

/** An exception's `URLError` equivalent and type: never its message, which can carry the URL. */
internal fun logDescription(error: Throwable): String {
    if (error is kotlin.coroutines.cancellation.CancellationException) return "cancelled"
    val type = error::class.java.simpleName
    var description = UrlErrorCode.fromThrowable(error)?.let { "URLError $it ($type)" } ?: type
    error.cause?.let { description += " (${it::class.java.simpleName})" }
    return description
}

internal inline fun <reified T> encodedBody(value: T, name: String = "request"): ByteArray =
    try { WireJson.encodeToString(serializer<T>(), value).toByteArray() } catch (_: Exception) {
        throw OpenCodeClientError("The $name could not be encoded.")
    }

/** Decodes with a JSON-element walker; any shape mismatch is the V2-contract error. */
internal fun <T> decoded(data: ByteArray, name: String = "server response", decode: (JsonElement) -> T): T =
    try { decode(WireJson.parseToJsonElement(data.decodeToString())) } catch (_: Exception) {
        throw OpenCodeClientError("The $name does not match the current OpenCode V2 contract.")
    }

internal fun <T> decoded(data: ByteArray, serializer: KSerializer<T>, name: String = "server response"): T =
    decoded(data, name) { WireJson.decodeFromJsonElement(serializer, it) }

internal inline fun <reified T> decodedAs(data: ByteArray, name: String = "server response"): T =
    decoded(data, serializer<T>(), name)

/** Runs [call], reading the whole body on OkHttp's thread; cancelling the coroutine cancels the call. */
internal suspend fun Call.awaitResponse(): RawResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { RawResponse(it.code, it.headers, it.body.bytes(), it.request.url) }
            } catch (e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
                return
            }
            continuation.resume(result)
        }
    })
}

internal fun OkHttpClient.withTimeout(timeout: Duration, followsRedirects: Boolean = true): OkHttpClient {
    val millis = timeout.inWholeMilliseconds
    return newBuilder()
        .connectTimeout(millis, TimeUnit.MILLISECONDS)
        .readTimeout(millis, TimeUnit.MILLISECONDS)
        .writeTimeout(millis, TimeUnit.MILLISECONDS)
        .followRedirects(followsRedirects)
        .followSslRedirects(followsRedirects)
        .build()
}

internal fun requestBody(method: String, body: ByteArray?): RequestBody? = when {
    body != null -> body.toRequestBody(JSON_MEDIA_TYPE)
    method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
    else -> null
}

/** The status error copy shared by every route. */
internal fun statusError(status: Int, retryAfterHeader: String?, locationMissing: Boolean = false): OpenCodeClientError = OpenCodeClientError(
    message = when {
        status == 401 -> localized(R.string.server_error_credentials_rejected)
        locationMissing -> OpenCodeClientError.FOLDER_GONE_MESSAGE
        else -> localized(R.string.server_error_http_status, status)
    },
    statusCode = status,
    retryAfter = OpenCodeClientError.retryAfter(retryAfterHeader),
)

/** A transport exception as the client error retries classify. */
internal fun transportError(error: Throwable, transport: TransportFailure?): OpenCodeClientError {
    // Cancellation (and failures sending again can't fix) carry no transport, so no loop retries them.
    val failure = transport?.let(OpenCodeClientError::unreachable) ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
    return failure.copy(urlErrorCode = UrlErrorCode.fromThrowable(error))
}

internal suspend inline fun <reified T> request(
    path: String,
    query: List<Pair<String, String>> = emptyList(),
    method: String = "GET",
    body: ByteArray? = null,
    timeout: Duration = 10.seconds,
    idempotency: RequestIdempotency? = null,
    retry: RetryPolicy? = null,
    followsRedirects: Boolean = true,
    isConnectionProbe: Boolean = false,
    server: OpenCodeServer,
): T {
    val response = perform(
        path, query, method, body, timeout, idempotency, retry, followsRedirects,
        isConnectionProbe = isConnectionProbe, server = server,
    )
    return decodedAs(response.data)
}

/** [request] with the failure captured as a value. */
internal suspend inline fun <reified T> capturedRequest(
    path: String,
    query: List<Pair<String, String>> = emptyList(),
    timeout: Duration = 10.seconds,
    server: OpenCodeServer,
): Result<T> = try { Result.success(request<T>(path, query, timeout = timeout, server = server)) } catch (error: OpenCodeClientError) {
    Result.failure(error)
}

/**
 * Sends one request under the shared retry policy ([NetworkRetry]): transient failures are
 * sent again with backoff, a non-idempotent request only when it provably never reached the
 * server. [idempotency] defaults from the method; POSTs the server dedupes opt in.
 */
internal suspend fun perform(
    path: String,
    query: List<Pair<String, String>> = emptyList(),
    method: String = "GET",
    body: ByteArray? = null,
    timeout: Duration = 10.seconds,
    idempotency: RequestIdempotency? = null,
    retry: RetryPolicy? = null,
    followsRedirects: Boolean = true,
    longPoll: Boolean = false,
    isConnectionProbe: Boolean = false,
    server: OpenCodeServer,
): MeasuredResponse {
    val url = server.endpoint(path, query) ?: throw OpenCodeClientError(localized(R.string.server_error_invalid_url))
    val contact = server.persistenceID
    val idempotency = idempotency ?: RequestIdempotency.forMethod(method)
    // Only a read that can be sent again is cut off when the link changes: not a send or a reply
    // (whether it acted would be unknown), not a GET that spends something (a pairing code), not
    // a long poll, and not a probe, which tests the one address it was given.
    val interruptible = method == "GET" && !longPoll && !isConnectionProbe && idempotency == RequestIdempotency.Idempotent
    val link = dependency(ServerLinkClient)
    try {
        return NetworkRetry.run(
            retry ?: NetworkRetry.currentPolicy(),
            idempotency = idempotency,
            label = "$method ${loggablePath(path)}",
            reachable = { ServerContact.shared.next(contact) },
        ) {
            // Each attempt goes to whichever address answers now, so a retry follows the server
            // to the address a race just found. A probe tests the one address it was given.
            // Read before routing, so a change while the route is found counts.
            val epoch = link.epoch()
            val target = if (isConnectionProbe) server else link.route(server)
            val targetURL = target.endpoint(path, query) ?: url
            val sentAt = Clock.liveValue.now()
            try {
                performOnce(
                    targetURL, path, method, body, timeout, followsRedirects, longPoll, isConnectionProbe,
                    interruptibleSince = if (interruptible) epoch else null, server = target,
                )
            } catch (error: OpenCodeClientError) {
                // The address in use didn't answer: the next attempt races again.
                val transport = error.transport
                if (!isConnectionProbe && transport != null) {
                    if (transport == TransportFailure.NotSent) {
                        link.invalidate(server, target.url)
                    } else {
                        link.requestFailed(server, target.url, sentAt)
                    }
                }
                throw error
            }
        }
    } catch (error: OpenCodeClientError) {
        var failure = error
        if (isConnectionProbe && currentCoroutineContext().isActive) {
            val addresses = if (failure.statusCode == null) ConnectionFailureDetail.resolve(url.host) else emptyList()
            if (currentCoroutineContext().isActive) {
                val detail = ConnectionFailureDetail(url, failure, addresses)
                failure = failure.copy(connectionFailures = listOf(detail))
                AppLog.network.notice { "Connection probe: ${detail.logSummary}" }
            }
        }
        throw failure
    }
}

private suspend fun performOnce(
    url: HttpUrl,
    path: String,
    method: String,
    body: ByteArray?,
    timeout: Duration,
    followsRedirects: Boolean,
    longPoll: Boolean,
    isConnectionProbe: Boolean,
    interruptibleSince: ServerLinkEpoch?,
    server: OpenCodeServer,
): MeasuredResponse {
    val requestID = UUID.randomUUID().toString().uppercase()
    val correlation = NetworkRetry.correlation.current() ?: NetworkRetry.Correlation()
    val clock = dependency(Clock)
    val started = clock.now()
    val transport = dependency(OpenCodeTransport)
    val link = dependency(ServerLinkClient)
    val builder = Request.Builder().url(url)
        .method(method, requestBody(method, body))
        .header("Accept", "application/json")
    server.authorize(builder)
    applyCapabilities(builder)
    correlation.identify(builder, requestID)
    // A read hung on a socket the phone has moved away from goes again on fresh pools or the
    // server's new address instead of waiting out its timeout (`perform` decides which requests
    // may be cut off).
    val response = try {
        val send: suspend () -> RawResponse = {
            val client = (if (longPoll) transport.longPoll else transport.request).withTimeout(timeout, followsRedirects)
            client.newCall(builder.build()).awaitResponse()
        }
        if (interruptibleSince != null) untilLinkChanges(interruptibleSince, server, link, send) else send()
    } catch (changed: LinkChanged) {
        AppLog.network.notice {
            "$method ${loggablePath(path)}: interrupted by a route or pool change after ${(clock.now() - started).logSummary}"
        }
        throw OpenCodeClientError.unreachable(TransportFailure.Interrupted)
    } catch (error: IOException) {
        val failure = TransportFailure.of(error, url, ServerContact.shared)
        if (failure == TransportFailure.TimedOut && !longPoll && !isConnectionProbe && !NetworkRetry.environment.current().isOffline()) {
            link.noteTimeout()
        }
        if (currentCoroutineContext().isActive) {
            AppLog.network.notice {
                "$method ${loggablePath(path)}: ${logDescription(error)} → ${failure?.rawValue ?: "not retryable"} after ${(clock.now() - started).logSummary}"
            }
        }
        throw transportError(error, failure)
    }
    if (response.code !in 200..299) {
        val locationMissing = OpenCodeClientError.isLocationMissing(response.code, response.body)
        AppLog.network.notice {
            "$method ${loggablePath(path)}: HTTP ${response.code}${if (locationMissing) " (location missing)" else ""} after ${(clock.now() - started).logSummary}"
        }
        val rpcError = if (path.startsWith("/api/rpc/")) OpenCodeClientError.rpcError(response.body) else null
        throw statusError(response.code, response.headers["Retry-After"], locationMissing).copy(rpcError = rpcError)
    }
    ServerContact.shared.heard(server.persistenceID, response.url)
    link.noteAnswer(if (isConnectionProbe) null else server.persistenceID)
    return MeasuredResponse(response.body, requestID, correlation)
}

private class LinkChanged : Exception()

/**
 * [work], unless the connection pools are reset or [server] moves to another address after
 * [start] (read before the address was chosen): then it's cancelled and [LinkChanged] thrown.
 */
internal suspend fun <T> untilLinkChanges(
    start: ServerLinkEpoch,
    server: OpenCodeServer,
    link: ServerLinkClient,
    work: suspend () -> T,
): T = coroutineScope {
    val watch = launch {
        while (true) {
            // Checked before waiting too: a change may already have landed.
            val now = link.epoch()
            if (now.pools != start.pools || link.resolve(server).url != server.url) throw LinkChanged()
            link.changed(now.changes)
        }
    }
    try {
        work()
    } finally {
        watch.cancel()
    }
}
