package ai.opencode.server

import ai.opencode.core.strings.localized
import ai.opencode.server.model.OpenCodeRpcError
import ai.opencode.server.retry.TransportFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one error every client call throws. A value type (`copy` to change it); the message is
 * user-facing copy.
 */
data class OpenCodeClientError(
    override val message: String,
    val statusCode: Int? = null,
    /** Seconds the server asked to wait before trying again (`Retry-After`). */
    val retryAfter: Double? = null,
    /** The server predates the route; callers hide the feature rather than retry. */
    val isUnsupported: Boolean = false,
    /** Set when no HTTP status arrived: how the connection failed. */
    val transport: TransportFailure? = null,
    /** How many times the request was sent before this error ended it. */
    val attempts: Int = 1,
    /** An earlier attempt failed after it may have reached the server. */
    val earlierAttemptMayHaveArrived: Boolean = false,
    /** A prompt whose sending ended in doubt and that the server didn't show when read back. */
    val unconfirmedPromptID: String? = null,
    val connectionFailures: List<ConnectionFailureDetail> = emptyList(),
    /** The `URLError` code equivalent of the transport exception (see [UrlErrorCode]). */
    val urlErrorCode: Int? = null,
    /** The request this answered was already settled, or is gone, on the server: trying again can't help. */
    val requestSettled: Boolean = false,
    /** The tagged `RpcError` an `/api/rpc` route answered with. */
    val rpcError: OpenCodeRpcError? = null,
) : Exception(message) {

    /**
     * The server answered `LocationNotFoundError`: the folder the request is scoped to (a
     * session's, say) no longer exists on the computer.
     */
    val isLocationMissing: Boolean get() = message == FOLDER_GONE_MESSAGE

    val isRetryableWaitError: Boolean
        get() {
            if (isUnsupported) return false
            statusCode?.let { return it == 408 || it == 429 || it >= 500 }
            val lowercased = message.lowercase()
            return !lowercased.startsWith("run failed:") &&
                !lowercased.contains("no longer in the recent transcript") &&
                !lowercased.contains("does not match the current opencode")
        }

    /**
     * The request may have been carried out though no success came back: the connection
     * broke or timed out after it was sent, or a proxy answered 502/504 (or a Cloudflare
     * origin error). A mutation that isn't repeatable reads the server's state instead.
     */
    val mayHaveReachedServer: Boolean
        get() {
            if (earlierAttemptMayHaveArrived) return true
            transport?.let { return it != TransportFailure.NotSent }
            return statusCode?.let(::isGatewayFailure) ?: false
        }

    /** The error's status, transport and attempts: never the server's message text. */
    val logSummary: String
        get() {
            val parts = mutableListOf<String>()
            statusCode?.let { parts += "HTTP $it" }
            transport?.let { parts += "transport ${it.rawValue}" }
            if (isUnsupported) parts += "unsupported"
            if (parts.isEmpty()) parts += if (message == UNREACHABLE_MESSAGE) "unclassified connection error" else "client error"
            retryAfter?.let { parts += "Retry-After ${formatUpToOneFraction(it)} s" }
            if (attempts > 1) parts += "after $attempts attempts"
            return parts.joinToString(", ")
        }

    // Exceptions are values here; skip the stack walk Kotlin would do on every copy.
    override fun fillInStackTrace(): Throwable = this

    companion object {
        val UNREACHABLE_MESSAGE: String get() = localized(R.string.server_error_unreachable)
        val FOLDER_GONE_MESSAGE: String get() = localized(R.string.server_error_folder_gone)

        /** A 404 whose body is the server's tagged `LocationNotFoundError`. */
        fun isLocationMissing(status: Int, body: ByteArray): Boolean {
            if (status != 404) return false
            val tag = runCatching {
                ((Json.parseToJsonElement(body.decodeToString()) as? JsonObject)?.get("_tag") as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            }.getOrNull()
            return tag == "LocationNotFoundError"
        }

        /** A `{"_tag":"RpcError","type":…,"data":{…}}` body; never its message, which is the server's text. */
        fun rpcError(body: ByteArray): OpenCodeRpcError? = runCatching {
            val root = Json.parseToJsonElement(body.decodeToString()) as? JsonObject ?: return null
            if ((root["_tag"] as? JsonPrimitive)?.content != "RpcError") return null
            val type = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val revision = ((root["data"] as? JsonObject)?.get("currentRevision") as? JsonPrimitive)?.content?.toLongOrNull()
            OpenCodeRpcError(type, revision)
        }.getOrNull()

        /** A request that got no answer from the server. */
        fun unreachable(transport: TransportFailure) = OpenCodeClientError(UNREACHABLE_MESSAGE, transport = transport)

        val uploadsUnsupported: OpenCodeClientError get() = OpenCodeClientError(
            localized(R.string.server_error_uploads_unsupported),
            isUnsupported = true,
        )

        /**
         * A proxy in front of the server couldn't get an answer from it: 502 and 504, and
         * Cloudflare's origin errors (520–527 and 530).
         */
        fun isGatewayFailure(status: Int): Boolean = status == 502 || status == 504 || status in 520..527 || status == 530

        private val httpDate = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

        /** A `Retry-After` value: seconds, or an HTTP date. */
        fun retryAfter(header: String?, now: Instant = Instant.now()): Double? {
            val text = header?.trim()?.ifEmpty { null } ?: return null
            text.toDoubleOrNull()?.let { if (it.isFinite()) return maxOf(0.0, it) }
            val parsed = try { ZonedDateTime.parse(text, httpDate).toInstant() } catch (_: Exception) {
                try { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() } catch (_: Exception) { null }
            } ?: return null
            return maxOf(0.0, (parsed.toEpochMilli() - now.toEpochMilli()) / 1000.0)
        }

        /**
         * `formatted(.number.precision(.fractionLength(0...1)).locale(.posix))`: grouped
         * ("1,234.5") and rounded half to even, as Foundation formats.
         */
        private fun formatUpToOneFraction(value: Double): String =
            DecimalFormat("#,##0.#", DecimalFormatSymbols(Locale.US)).apply { roundingMode = RoundingMode.HALF_EVEN }.format(value)
    }
}
