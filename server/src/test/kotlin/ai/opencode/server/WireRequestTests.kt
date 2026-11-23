package ai.opencode.server

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.LOCATION_BOOT_TIMEOUT
import ai.opencode.server.live.WAIT_POLL_TIMEOUT
import ai.opencode.server.model.FileReadTarget
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeShellOutputPage
import ai.opencode.server.model.OpenCodeRpcError
import ai.opencode.server.model.OpenCodeSessionGroup
import ai.opencode.server.model.OpenCodeSessionGroupOutcomes
import ai.opencode.server.model.OpenCodeSessionGroupSummary
import ai.opencode.server.model.OpenCodeSessionGroupsSnapshot
import ai.opencode.server.model.OpenCodeSessionGroupsWrite
import ai.opencode.server.model.PromptContext
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.withRetryPolicy
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import okhttp3.ResponseBody.Companion.toResponseBody

/** Exact request bodies and failure handling for the live HTTP transport, captured below OkHttp. */
class WireRequestTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private suspend fun <T> withFixture(fixture: WireFixture, block: suspend (WireFixture) -> T): T =
        try { block(fixture) } finally { fixture.close() }

    private suspend fun expectClientError(block: suspend () -> Unit): OpenCodeClientError {
        try {
            block()
        } catch (error: OpenCodeClientError) {
            return error
        }
        fail("Expected an OpenCodeClientError")
        throw AssertionError()
    }

    @Test
    fun phoneSendCarriesItsNotificationCallback() = runTest {
        withFixture(WireFixture { Reply.Json(200, "{}") }) { fixture ->
            val callback = "https://relay.example/v2/notify/sealed"

            PromptContext.notificationCallbackURL.withValue(callback) {
                OpenCodeClient.liveValue.sendPrompt(fixture.server, "ses_1", "hello", emptyList(), emptyList(), "msg_1", OpenCodeDelivery.Steer)
            }

            val request = fixture.requests.first()
            assertEquals(
                json("""{"id":"msg_1","text":"hello","delivery":"steer","metadata":{"notification":{"callbackURL":"$callback"}}}"""),
                request.json(),
            )
        }
    }

    @Test
    fun aSendToAFolderThatIsGoneSaysSo() = runTest {
        val body = """{"_tag":"LocationNotFoundError","location":{"directory":"/work/linked"},"message":"Location not found: /work/linked"}"""
        withFixture(WireFixture { Reply.Json(404, body) }) { fixture ->
            val error = expectClientError {
                OpenCodeClient.liveValue.sendPrompt(fixture.server, "ses_1", "hello", emptyList(), emptyList(), "msg_1", OpenCodeDelivery.Steer)
            }
            assertTrue(error.isLocationMissing)
            assertEquals(404, error.statusCode)
            assertEquals(OpenCodeClientError.FOLDER_GONE_MESSAGE, error.message)
        }
        // A bare 404 (a missing route or message) isn't a missing folder.
        assertFalse(OpenCodeClientError.isLocationMissing(status = 404, body = ByteArray(0)))
        assertFalse(OpenCodeClientError.isLocationMissing(status = 404, body = """{"_tag":"NotFoundError"}""".encodeToByteArray()))
        assertFalse(OpenCodeClientError.isLocationMissing(status = 400, body = """{"_tag":"LocationNotFoundError"}""".encodeToByteArray()))
    }

    @Test
    fun movingASessionPostsItsNewFolderOnce() = runTest {
        withFixture(WireFixture { Reply.status(204) }) { fixture ->
            OpenCodeClient.liveValue.moveSession(fixture.server, "ses_1", "/work/repo")

            val request = fixture.requests.first()
            assertEquals(1, fixture.requests.size)
            assertEquals("POST /api/session/ses_1/move", request.route)
            assertEquals(json("""{"directory":"/work/repo"}"""), request.json())
        }
    }

    @Test
    fun previewEnrollmentExchangesOnlyPublicKeysOnTheSavedHTTPSOrigin() = runTest {
        withFixture(WireFixture { Reply.Json(200, """{"output":{"publicKey":"server-public"}}""") }) { fixture ->
            val url = "https://relay.example/v2/notify/sealed"
            var server = fixture.server.copy(url = "http://local.example", alternateURLs = listOf(fixture.server.url))
            val key = OpenCodeClient.pairNotificationPreview(server, callbackURL = url, ref = "ref", publicKey = "phone-public")
            assertEquals("server-public", key)
            val request = fixture.requests.first()
            assertEquals("POST /api/rpc/mobile-notifications/pair", request.route)
            assertEquals(json("""{"input":{"ref":"ref","publicKey":"phone-public","callbackURL":"$url"}}"""), request.json())
            server = server.copy(alternateURLs = emptyList())
            expectClientError { OpenCodeClient.pairNotificationPreview(server, callbackURL = url, ref = "ref", publicKey = "phone-public") }
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun notifyPluginStatusReadsThePluginList() = runTest {
        for ((body, expected) in listOf(
            """{"data":[{"id":"opencode.plan","state":{"status":"active"}},{"source":{"type":"x"}},{"id":"opencode-notify-callback","state":{"status":"active"}}]}""" to OpenCodeNotifyPluginStatus.Active,
            """{"data":[{"id":"opencode-notify-callback","state":{"status":"failed"}}]}""" to OpenCodeNotifyPluginStatus.Inactive,
            """{"data":[{"id":"opencode.plan","state":{"status":"active"}}]}""" to OpenCodeNotifyPluginStatus.Missing,
        )) {
            withFixture(WireFixture { Reply.Json(200, body) }) { fixture ->
                val status = OpenCodeClient.liveValue.notifyPluginStatus(fixture.server)

                assertEquals(expected, status)
                val request = fixture.requests.first()
                assertEquals("GET", request.method)
                assertEquals("/api/plugin", request.path)
                assertNull("A location-scoped read would boot that location's services", request.query)
            }
        }
    }

    /** Stop asks the server to resume steering messages, as the web app and the TUI do, and reads whether a run was going. */
    @Test
    fun interruptResumesSteeringAndReadsWhetherARunWasGoing() = runTest {
        for (interrupted in listOf(true, false)) {
            withFixture(WireFixture { Reply.Json(200, """{"interrupted":$interrupted}""") }) { fixture ->
                val result = OpenCodeClient.liveValue.interrupt(fixture.server, "ses_1")

                assertEquals(interrupted, result)
                val request = fixture.requests.first()
                assertEquals(1, fixture.requests.size)
                assertEquals("POST", request.method)
                assertEquals("/api/session/ses_1/interrupt", request.path)
                assertEquals(listOf("resume" to "true"), request.queryItems)
            }
        }
    }

    @Test
    fun interruptWithoutItsAnswerIsAnError() = runTest {
        withFixture(WireFixture { Reply.Json(200, "{}") }) { fixture ->
            expectClientError { OpenCodeClient.liveValue.interrupt(fixture.server, "ses_1") }
        }
    }

    @Test
    fun sendsWithoutACallbackCarryNoNotificationMetadata() = runTest {
        withFixture(WireFixture { Reply.Json(200, "{}") }) { fixture ->
            OpenCodeClient.liveValue.sendDeliveredPrompt(fixture.server, "ses_1", "steer", OpenCodeDelivery.Steer)

            val body = fixture.requests.first().json()
            assertNull(body["metadata"])
        }
    }

    @Test
    fun composerPromptPostsFilesAndExplicitDelivery() = runTest {
        for (delivery in listOf(OpenCodeDelivery.Steer, OpenCodeDelivery.Queue)) {
            withFixture(WireFixture { Reply.Json(200, "{}") }) { fixture ->
                val image = OpenCodeOutgoingAttachment(
                    data = byteArrayOf(1, 2), mime = "image/png", videoDuration = 4.0,
                    localVideoURI = "content://media/example/movie",
                )

                OpenCodeClient.liveValue.sendPrompt(fixture.server, "ses_1", "hello", listOf(image), emptyList(), "msg_1", delivery)

                val request = fixture.requests.first()
                assertEquals(1, fixture.requests.size)
                assertEquals("POST", request.method)
                assertEquals("/api/session/ses_1/prompt", request.path)
                assertEquals(
                    json("""{"id":"msg_1","text":"hello","files":[{"uri":"${image.dataURL}"}],"delivery":"${delivery.rawValue}"}"""),
                    request.json(),
                )
            }
        }
    }

    @Test
    fun voicePromptPostsDeliveryWithGeneratedID() = runTest {
        withFixture(WireFixture { Reply.Json(200, "{}") }) { fixture ->
            val messageID = OpenCodeClient.liveValue.sendDeliveredPrompt(fixture.server, "ses_1", "steer", OpenCodeDelivery.Steer)

            val request = fixture.requests.first()
            assertTrue(messageID.startsWith("msg_"))
            assertEquals("/api/session/ses_1/prompt", request.path)
            assertEquals(json("""{"id":"$messageID","text":"steer","delivery":"steer"}"""), request.json())
        }
    }

    @Test
    fun runningShellsReadOnlyTheSessionLocation() = runTest {
        val fixture = WireFixture {
            Reply.Json(200, """{"location":{"directory":"/a b"},"data":[{"id":"sh_1","status":"running","command":"bun dev","cwd":"/a b","shell":"/bin/zsh","file":"/tmp/o","metadata":{"sessionID":"ses_1"},"time":{"started":1}},{"id":"sh_2","status":"exited","command":"ls","cwd":"/a b","shell":"/bin/zsh","file":"/tmp/p","metadata":{},"time":{"started":1}}]}""")
        }
        withFixture(fixture) {
            val shells = OpenCodeClient.liveValue.runningShells(fixture.server, "/a b")

            assertEquals(
                listOf(OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = "ses_1", isListed = true, startedAt = Instant.ofEpochMilli(1))),
                shells,
            )
            val request = fixture.requests.first()
            assertEquals("GET", request.method)
            assertEquals("/api/shell", request.path)
            assertEquals(listOf("location[directory]" to "/a b"), request.queryItems)
        }
    }

    @Test
    fun runningJobsReadTheSessionTreeAndCancelPosts() = runTest {
        val fixture = WireFixture { request ->
            when (request.method to request.path) {
                "GET" to "/api/experimental/job" -> Reply.Json(
                    200,
                    """{"data":[{"kind":"codemode","id":"cm_1","sessionID":"ses_1","status":"running","time":{"started":5}},{"kind":"subagent","id":"job_2","sessionID":"ses_1","status":"running","childSessionID":"ses_2","agent":"general","description":"Look","time":{"started":6}}]}""",
                )
                else -> Reply.Json(204, "")
            }
        }
        withFixture(fixture) {
            val jobs = OpenCodeClient.liveValue.runningJobs(fixture.server, "ses_1")
            assertEquals(
                listOf(
                    OpenCodeJob(id = "cm_1", kind = OpenCodeJob.Kind.CodeMode, sessionID = "ses_1", isRunning = true, startedAt = Instant.ofEpochMilli(5)),
                    OpenCodeJob(
                        id = "job_2", kind = OpenCodeJob.Kind.Subagent, sessionID = "ses_1", isRunning = true,
                        childSessionID = "ses_2", startedAt = Instant.ofEpochMilli(6),
                    ),
                ),
                jobs,
            )
            OpenCodeClient.liveValue.cancelJob(fixture.server, "cm_1")

            val requests = fixture.requests
            assertEquals(listOf("GET /api/experimental/job", "POST /api/experimental/job/cm_1/cancel"), requests.map { it.route })
            assertEquals(listOf("sessionID" to "ses_1", "descendants" to "true", "status" to "running"), requests[0].queryItems)
            assertTrue(requests[1].queryItems.isEmpty())
        }
    }

    @Test
    fun shellDetailOutputAndStopUseTheShellRoutes() = runTest {
        val fixture = WireFixture { request ->
            when (request.method to request.path) {
                "GET" to "/api/shell/sh_1" ->
                    Reply.Json(200, """{"location":{"directory":"/a b"},"data":{"id":"sh_1","status":"exited","command":"bun test","cwd":"/a b","shell":"/bin/zsh","file":"/tmp/o","exit":1,"metadata":{"sessionID":"ses_1"},"time":{"started":1000,"completed":4000}}}""")
                "GET" to "/api/shell/sh_1/output" ->
                    Reply.Json(200, """{"location":{"directory":"/a b"},"data":{"output":"FAIL\n","cursor":105,"size":105,"truncated":false}}""")
                else -> Reply.Json(204, "")
            }
        }
        withFixture(fixture) {
            val shell = OpenCodeClient.liveValue.shell(fixture.server, "/a b", "sh_1")
            assertEquals(OpenCodeShell.Status.Exited, shell.status)
            assertEquals(1, shell.exitCode)
            assertEquals(Instant.ofEpochSecond(1), shell.startedAt)
            assertEquals(Instant.ofEpochSecond(4), shell.completedAt)
            val page = OpenCodeClient.liveValue.shellOutput(fixture.server, "/a b", "sh_1", 100, 65_536)
            assertEquals(OpenCodeShellOutputPage(output = "FAIL\n", cursor = 105, size = 105), page)
            OpenCodeClient.liveValue.stopShell(fixture.server, "/a b", "sh_1")
            OpenCodeClient.liveValue.moveToBackground(fixture.server, "ses_1")

            val requests = fixture.requests
            assertEquals(
                listOf("GET /api/shell/sh_1", "GET /api/shell/sh_1/output", "DELETE /api/shell/sh_1", "POST /api/session/ses_1/background"),
                requests.map { it.route },
            )
            assertEquals(
                listOf("location[directory]" to "/a b", "cursor" to "100", "limit" to "65536"),
                requests[1].queryItems,
            )
            assertEquals(listOf("location[directory]" to "/a b"), requests[2].queryItems)
        }
    }

    @Test
    fun serverEndpointReplacesPathQueryAndFragment() {
        val server = OpenCodeServer(url = "  https://host.example:4096/base?token=x#part \n", username = "u", password = "p")
        assertEquals("https://host.example:4096/api/event", server.endpoint("/api/event")?.toString())
        assertEquals(
            "https://host.example:4096/api/model?directory=/a%20b",
            server.endpoint("/api/model", listOf("directory" to "/a b"))?.toString(),
        )
        assertEquals("Basic " + Base64.getEncoder().encodeToString("u:p".toByteArray()), server.basicAuthorization)
        assertNull(OpenCodeServer(url = "https://host.example", username = "u", password = "").basicAuthorization)
        for (url in listOf("ftp://example.invalid", "https://", "not a url")) {
            assertNull(url, OpenCodeServer(url = url, username = "", password = "").endpoint("/api/event"))
        }
    }

    @Test
    fun imageRequestReadsProjectFilesThroughTheServer() {
        val server = OpenCodeServer(url = "https://host.example:4096", username = "u", password = "p")
        val file = server.imageRequest("/work/proj/shots/a b/ü 100%.png", "/work/proj")
        assertEquals(
            "https://host.example:4096/api/fs/read/shots/a%20b/%C3%BC%20100%25.png?location%5Bdirectory%5D=/work/proj",
            file?.url?.toString(),
        )
        assertEquals(server.basicAuthorization, file?.header("Authorization"))
        fun decodedPath(source: String) = server.imageRequest(source, "/work/proj")?.url?.let { "/" + it.pathSegments.joinToString("/") }
        assertEquals("/api/fs/read/a b.png", decodedPath("file:///work/proj/a%20b.png"))
        assertEquals("/api/fs/read/docs/x.png", decodedPath("sandbox:docs/x.png"))
        assertNull(server.imageRequest("/elsewhere/x.png", "/work/proj"))
        assertNull(server.imageRequest("x.png", null))

        val remote = server.imageRequest("https://cdn.example/x.png", null)
        assertEquals("https://cdn.example/x.png", remote?.url?.toString())
        assertNull(remote?.header("Authorization"))
    }

    @Test
    fun retryReadsAnOutsideMarkdownImageFromItsOwnFolderAgain() = runTest {
        withFixture(WireFixture { Reply.status(404) }) { fixture ->
            val client = OpenCodeClient.testValue
            val target = FileReadTarget("/elsewhere", "missing.png")
            repeat(2) {
                assertNull(client.markdownImage(fixture.server, "/work/proj", "/elsewhere/missing.png", target))
            }
            assertEquals(2, fixture.requests.size)
            for (request in fixture.requests) {
                assertEquals("GET /api/fs/read/missing.png", request.route)
                assertEquals(listOf("location[directory]" to "/elsewhere"), request.queryItems)
            }
        }
    }

    private fun voiceHandoff(instruction: String): String =
        "Voice delegation. The user is on a voice call with a voice agent, which hands their requests to you.\n" +
            "{\n  \"instruction\" : \"$instruction\",\n  \"transcript_delta\" : [\n\n  ]\n}"

    @Test
    fun assistantReplyStopsAtNextUserMessageSoSteersDoNotDuplicateFinalAnswer() = runTest {
        fun message(id: String, type: String, text: String?): String {
            val part = if (text == null) """{"type":"text"}""" else """{"type":"text","text":${Json.encodeToString(String.serializer(), text)}}"""
            return """{"id":"$id","type":"$type","time":{"created":1},"content":[$part]}"""
        }
        val body = """{"data":[""" + listOf(
            message("msg_a4", "assistant", "Final answer after typed steer."),
            message("msg_typed", "user", "Typed composer steer mid-run"),
            message("msg_a3", "assistant", null),
            message("msg_3", "user", voiceHandoff("Third voice request")),
            message("msg_a2", "assistant", "Interim answer for second request."),
            message("msg_2", "user", voiceHandoff("Second voice request")),
            message("msg_a1", "assistant", null),
            message("msg_1", "user", voiceHandoff("First voice request")),
        ).joinToString(",") + "]}"
        withFixture(WireFixture { Reply.Json(200, body) }) { fixture ->
            val first = OpenCodeClient.liveValue.assistantReply(fixture.server, "ses_1", "msg_1")
            val second = OpenCodeClient.liveValue.assistantReply(fixture.server, "ses_1", "msg_2")
            val third = OpenCodeClient.liveValue.assistantReply(fixture.server, "ses_1", "msg_3")

            assertNull("msg_1 had no text before the next voice delegation msg_2; must not steal msg_2's reply", first)
            assertEquals("Interim answer for second request.", second)
            assertEquals(
                "A typed composer steer mid-run must still return the final assistant reply for msg_3",
                "Final answer after typed steer.",
                third,
            )
        }
    }

    @Test
    fun waitForIdleRewaitsWhenWaitTimesOutWhileSessionRemainsActive() = runTest {
        val waitAttempts = AtomicInteger(0)
        val fixture = WireFixture { request ->
            when (request.method to request.path) {
                "POST" to "/api/experimental/session/ses_1/wait" ->
                    if (waitAttempts.incrementAndGet() < 3) Reply.Json(500, "{}") else Reply.Json(204, "")
                "GET" to "/api/session/active" -> Reply.Json(200, """{"data":{"ses_1":{"type":"busy"}}}""")
                else -> Reply.Json(404, "{}")
            }
        }
        withFixture(fixture) {
            withDependenciesSuspending({ this[Clock] = ImmediateClock() }) {
                OpenCodeClient.liveValue.waitForIdle(fixture.server, "ses_1")
            }
            assertEquals(3, waitAttempts.get())
        }
    }

    @Test
    fun waitForIdleDoesNotRetryClientErrorsAndBoundsFastActiveRetries() = runTest {
        val fourXXAttempts = AtomicInteger(0)
        val activeChecks = AtomicInteger(0)
        val nonRetryable = WireFixture { request ->
            when (request.method to request.path) {
                "POST" to "/api/experimental/session/ses_1/wait" -> {
                    fourXXAttempts.incrementAndGet()
                    Reply.Json(404, "{}")
                }
                "GET" to "/api/session/active" -> {
                    activeChecks.incrementAndGet()
                    Reply.Json(200, """{"data":{"ses_1":{"type":"busy"}}}""")
                }
                else -> Reply.Json(404, "{}")
            }
        }
        withFixture(nonRetryable) {
            expectClientError { OpenCodeClient.liveValue.waitForIdle(nonRetryable.server, "ses_1") }
            assertEquals("4xx (other than 408/429) must not retry", 1, fourXXAttempts.get())
            assertEquals(0, activeChecks.get())
        }

        val fastFailAttempts = AtomicInteger(0)
        val wedgedActiveChecks = AtomicInteger(0)
        val wedged = WireFixture { request ->
            when (request.method to request.path) {
                "POST" to "/api/experimental/session/ses_1/wait" -> {
                    fastFailAttempts.incrementAndGet()
                    Reply.Json(500, "{}")
                }
                "GET" to "/api/session/active" -> {
                    wedgedActiveChecks.incrementAndGet()
                    Reply.Json(200, """{"data":{"ses_1":{"type":"busy"}}}""")
                }
                else -> Reply.Json(404, "{}")
            }
        }
        withFixture(wedged) {
            expectClientError {
                withDependenciesSuspending({ this[Clock] = ImmediateClock() }) {
                    OpenCodeClient.liveValue.waitForIdle(wedged.server, "ses_1")
                }
            }
            assertEquals("Fast-failing /wait with active=true must terminate at the pass bound", 12, fastFailAttempts.get())
            assertEquals(11, wedgedActiveChecks.get())
        }
    }

    @Test
    fun waitForIdleReturnsImmediatelyWhenWaitFailsAndSessionIsNoLongerActive() = runTest {
        assertEquals(25.seconds, WAIT_POLL_TIMEOUT)
        val waitAttempts = AtomicInteger(0)
        val activeChecks = AtomicInteger(0)
        val fixture = WireFixture { request ->
            when (request.method to request.path) {
                "POST" to "/api/experimental/session/ses_1/wait" -> {
                    waitAttempts.incrementAndGet()
                    Reply.Json(502, "{}")
                }
                "GET" to "/api/session/active" -> {
                    activeChecks.incrementAndGet()
                    Reply.Json(200, """{"data":{}}""")
                }
                "GET" to "/api/session/ses_1/message" -> Reply.Json(
                    200,
                    """
                    {"data":[
                      {"id":"msg_a1","type":"assistant","time":{"created":2},"content":[{"type":"text","text":"Finished during the network blip."}]},
                      {"id":"msg_1","type":"user","time":{"created":1},"content":[{"type":"text","text":"Run the tests"}]}
                    ]}
                    """.trimIndent(),
                )
                else -> Reply.Json(404, "{}")
            }
        }
        withFixture(fixture) {
            withDependenciesSuspending({ this[Clock] = ImmediateClock() }) {
                OpenCodeClient.liveValue.waitForIdle(fixture.server, "ses_1")
                val reply = OpenCodeClient.liveValue.assistantReply(fixture.server, "ses_1", "msg_1")
                assertEquals("Finished during the network blip.", reply)
            }
            assertEquals(1, waitAttempts.get())
            assertEquals(1, activeChecks.get())
        }
    }

    @Test
    fun modelCatalogUsesLocationBootTimeout() = runTest {
        val fixture = WireFixture { request ->
            when (request.method to request.path) {
                "GET" to "/api/model" -> Reply.Json(
                    200,
                    """{"data":[{"id":"gpt-5.4","modelID":"gpt-5.4","providerID":"openai","name":"GPT-5.4","enabled":true,"status":"active"}]}""",
                )
                "GET" to "/api/model/default" -> Reply.Json(
                    200,
                    """{"data":{"id":"gpt-5.4","modelID":"gpt-5.4","providerID":"openai","name":"GPT-5.4","enabled":true,"status":"active"}}""",
                )
                else -> Reply.Json(404, "{}")
            }
        }
        withFixture(fixture) {
            val catalog = OpenCodeClient.liveValue.modelCatalog(fixture.server, "/work/cold-boot")
            assertEquals(listOf("gpt-5.4"), catalog.models.map { it.modelID })
            assertEquals("gpt-5.4", catalog.defaultModel?.modelID)
            assertEquals(listOf("GET /api/model", "GET /api/model/default"), fixture.requests.map { it.route }.sorted())
            assertTrue(fixture.requests.all { it.queryValue("location[directory]") == "/work/cold-boot" })
            assertTrue(fixture.requests.all { it.timeoutInterval == LOCATION_BOOT_TIMEOUT.inWholeMilliseconds / 1000.0 })
        }
    }

    @Test fun homeAttentionAsksHotDirectoriesAtOnce() = runBlocking {
        val fixture = WireFixture { request ->
            if (request.queryValue("location[directory]") == "/booting") {
                Reply.Open(200, "")
            } else if (request.route == "GET /api/permission/request" || request.route == "GET /api/form") {
                Reply.Json(200, """{"data":[]}""")
            } else {
                Reply.Json(404, "{}")
            }
        }
        withFixture(fixture) {
            val held = launch(Dispatchers.Default) { OpenCodeClient.liveValue.homeAttention(fixture.server, emptyList(), listOf("/booting", "/ready")) }
            for (attempt in 0 until 500) {
                if (fixture.requests.count { it.queryValue("location[directory]") == "/ready" } >= 2) break
                delay(10)
            }
            held.cancel()
            assertEquals(2, fixture.requests.count { it.queryValue("location[directory]") == "/ready" })
        }
    }

    @Test fun modelCatalogReadsTheListAndDefaultTogether() = runBlocking {
        val fixture = WireFixture { request -> if (request.route == "GET /api/model") Reply.Open(200, "") else Reply.Json(200, "{}") }
        withFixture(fixture) {
            val held = launch(Dispatchers.Default) { OpenCodeClient.liveValue.modelCatalog(fixture.server, "/work/held") }
            for (attempt in 0 until 500) {
                if (fixture.requests.size >= 2) break
                delay(10)
            }
            held.cancel()
            assertEquals(setOf("GET /api/model", "GET /api/model/default"), fixture.requests.map { it.route }.toSet())
        }

        val failing = WireFixture { request ->
            if (request.route == "GET /api/model") {
                Reply.Json(200, """{"data":[{"id":"gpt-5.4","modelID":"gpt-5.4","providerID":"openai","name":"GPT-5.4","enabled":true,"status":"active"}]}""")
            } else {
                Reply.Json(404, "{}")
            }
        }
        withFixture(failing) {
            val catalog = OpenCodeClient.liveValue.modelCatalog(failing.server, "/work/no-default")
            assertEquals(listOf("gpt-5.4"), catalog.models.map { it.modelID })
            assertNull(catalog.defaultModel)
        }
    }

    /**
     * Plain HTTP reaches private networks and overlays only; the live guard refuses the rest as the
     * platform's cleartext refusal, before anything is sent.
     */
    @Test
    fun transportSecurityAllowsPrivateOverlays() {
        val allowed = listOf(
            "http://100.64.0.1:9", "http://100.96.0.1:9", "http://100.127.255.254:9",
            "http://[fc00::1]:9", "http://[fd7a:115c:a1e0::1]:9", "http://[fdff:ffff::1]:9",
            "http://peer.tailnet-0000.ts.net:9", "http://peer.netbird.cloud:9", "http://peer.netbird.selfhosted:9",
            "http://nas.home.arpa:9", "http://build.internal:9",
            // NSAllowsLocalNetworking itself: RFC 1918, loopback, unqualified and .local names.
            "http://10.0.2.2:4096", "http://192.168.1.20:4096", "http://172.31.7.134:4096", "http://127.0.0.1:4096",
            "http://my-mac:4096", "http://my-mac.local:4096",
        )
        val blocked = listOf(
            "http://100.63.255.254:9", "http://100.128.0.1:9", "http://8.8.8.8:9",
            "http://[fbff::1]:9", "http://[fe00::1]:9", "http://[2606:4700:4700::1111]:9",
            "http://opencode-ats-probe.example.com:9", "http://ts.net.example.com:9",
        )
        fun refused(address: String): Boolean {
            val client = okhttp3.OkHttpClient.Builder().addInterceptor(TransportSecurity.interceptor)
                .addInterceptor { chain ->
                    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(204).message("").body(ByteArray(0).toResponseBody()).build()
                }
                .build()
            return try {
                client.newCall(okhttp3.Request.Builder().url(address).build()).execute().close()
                false
            } catch (error: java.net.UnknownServiceException) {
                error.message.orEmpty().startsWith("CLEARTEXT communication to ")
            }
        }
        for (address in allowed) assertTrue("should allow $address", !refused(address))
        for (address in blocked) assertTrue("should block $address", refused(address))
        assertTrue("HTTPS is never refused", !refused("https://8.8.8.8:9"))
    }

    @Test
    fun sessionGroupsReadsWrapTheirInputAndUnwrapTheirOutput() = runTest {
        val fixture = WireFixture { request ->
            when (request.path) {
                "/api/rpc/session-groups/capabilities" -> Reply.Json(
                    200,
                    """{"output":{"apiVersion":2,"refresh":"list","operationIDs":true,"mutations":["create","assign","reorder","rename","remove","import"]}}""",
                )
                else -> Reply.Json(200, """{"output":{"revision":1,"groups":[{"id":"local-reading","name":"Reading","sessionIDs":["ses_example"]}]}}""")
            }
        }
        withFixture(fixture) {
            val capabilities = OpenCodeClient.liveValue.sessionGroupsCapabilities(it.server)
            assertEquals(2, capabilities.apiVersion)
            assertTrue(capabilities.operationIDs)
            assertEquals(setOf("create", "assign", "reorder", "rename", "remove", "import"), capabilities.mutations)

            val snapshot = OpenCodeClient.liveValue.sessionGroups(it.server)
            assertEquals(OpenCodeSessionGroupsSnapshot(1, listOf(OpenCodeSessionGroup("local-reading", "Reading", listOf("ses_example")))), snapshot)

            assertEquals(listOf("POST /api/rpc/session-groups/capabilities", "POST /api/rpc/session-groups/list"), it.requests.map { r -> r.route })
            for (request in it.requests) {
                assertEquals(json("""{"input":{}}"""), request.json())
                assertTrue(request.headers.entries.any { (name, value) -> name.equals("Content-Type", true) && value.startsWith("application/json") })
            }
        }
    }

    @Test
    fun sessionGroupsWritesSendTheContractsBodies() = runTest {
        withFixture(WireFixture { Reply.Json(200, """{"output":{"revision":3,"groups":[]}}""") }) { fixture ->
            val client = OpenCodeClient.liveValue
            val writes = listOf(
                OpenCodeSessionGroupsWrite.Create("Reading") to """{"name":"Reading"}""",
                OpenCodeSessionGroupsWrite.Assign(null, "ses_1") to """{"groupID":null,"sessionID":"ses_1"}""",
                OpenCodeSessionGroupsWrite.Assign("grp_1", "ses_1") to """{"groupID":"grp_1","sessionID":"ses_1"}""",
                OpenCodeSessionGroupsWrite.Reorder(listOf("b", "a")) to """{"groupIDs":["b","a"]}""",
                OpenCodeSessionGroupsWrite.Rename("a", "Work") to """{"groupID":"a","name":"Work"}""",
                OpenCodeSessionGroupsWrite.Remove("a") to """{"groupID":"a"}""",
                OpenCodeSessionGroupsWrite.Import(listOf(OpenCodeSessionGroup("local-reading", "Reading", listOf("ses_example")))) to
                    """{"groups":[{"id":"local-reading","name":"Reading","sessionIDs":["ses_example"]}]}""",
            )
            for ((write, input) in writes) {
                val snapshot = client.writeSessionGroups(fixture.server, write, 2, "op-${write.method}")
                assertEquals(3L, snapshot.revision)
                val request = fixture.requests.last()
                assertEquals("POST /api/rpc/session-groups/${write.method}", request.route)
                val expected = json(input).jsonObject + mapOf("expectedRevision" to JsonPrimitive(2), "operationID" to JsonPrimitive("op-${write.method}"))
                assertEquals(JsonObject(mapOf("input" to JsonObject(expected))), request.json())
            }
            client.writeSessionGroups(fixture.server, OpenCodeSessionGroupsWrite.Create("Old"), 0, null)
            assertEquals("an older plugin gets no operation ID", json("""{"input":{"name":"Old","expectedRevision":0}}"""), fixture.requests.last().json())
        }
    }

    @Test
    fun sessionGroupsErrorsCarryTheirTypeAndRevision() = runTest {
        val bodies = listOf(
            """{"_tag":"RpcError","type":"revision_conflict","message":"Expected revision 0, found 1. List session groups and retry.","data":{"currentRevision":1}}""" to
                OpenCodeRpcError(OpenCodeRpcError.REVISION_CONFLICT, 1),
            """{"_tag":"RpcError","type":"import_refused","message":"No.","data":{"currentRevision":4}}""" to OpenCodeRpcError(OpenCodeRpcError.IMPORT_REFUSED, 4),
            """{"_tag":"RpcError","type":"operation_conflict","message":"No.","data":{}}""" to OpenCodeRpcError(OpenCodeRpcError.OPERATION_CONFLICT),
            """{"_tag":"RpcError","type":"rpc.invalid_input","message":"Missing key"}""" to OpenCodeRpcError(OpenCodeRpcError.SCHEMA_INVALID),
        )
        for ((body, expected) in bodies) {
            withFixture(WireFixture { Reply.Json(400, body) }) { fixture ->
                val error = expectClientError {
                    OpenCodeClient.liveValue.writeSessionGroups(fixture.server, OpenCodeSessionGroupsWrite.Create("A"), 0, "op-1")
                }
                assertEquals(expected, error.rpcError)
                assertEquals(400, error.statusCode)
                assertEquals("a domain failure is never sent again", 1, fixture.requests.size)
            }
        }
        withFixture(WireFixture { Reply.Json(400, """{"_tag":"Other","type":"x"}""") }) { fixture ->
            assertNull(expectClientError { OpenCodeClient.liveValue.sessionGroups(fixture.server) }.rpcError)
        }
    }

    @Test
    fun sessionGroupsHierarchyReadsTheContractsShapes() = runTest {
        val fixture = WireFixture { request ->
            when (request.path) {
                "/api/rpc/session-groups/capabilities" -> Reply.Json(
                    200,
                    """{"output":{"apiVersion":2,"hierarchyVersion":1,"operationIDs":true,"mutations":["create","assign","reorder","rename","remove","import"]}}""",
                )
                "/api/rpc/session-groups/hierarchyList" -> Reply.Json(
                    200,
                    """{"output":{"revision":5,"groups":[{"id":"group_papers","name":"Papers","sessionIDs":[],"parentID":"reading"},""" +
                        """{"id":"reading","name":"Reading","sessionIDs":["ses_root"],"parentID":null}]}}""",
                )
                else -> Reply.Json(
                    200,
                    """{"output":{"revision":9,"groups":[{"groupID":"reading","lastActivityAt":200,"memberCount":2,"observedNonArchivedCount":2,"complete":true,""" +
                        """"lastExecutionOutcomes":{"succeeded":1,"failed":0,"interrupted":0,"unreported":1}},""" +
                        """{"groupID":"group_notes","lastActivityAt":null,"memberCount":1,"observedNonArchivedCount":0,"complete":false,""" +
                        """"lastExecutionOutcomes":{"succeeded":0,"failed":0,"interrupted":0,"unreported":0}}]}}""",
                )
            }
        }
        withFixture(fixture) {
            val client = OpenCodeClient.liveValue
            val capabilities = client.sessionGroupsCapabilities(it.server)
            assertEquals(1, capabilities.hierarchyVersion)
            assertTrue(capabilities.supportsHierarchy)

            assertEquals(
                "a child can come before its parent; both keep their parentID",
                OpenCodeSessionGroupsSnapshot(
                    5,
                    listOf(
                        OpenCodeSessionGroup("group_papers", "Papers", emptyList(), parentID = "reading"),
                        OpenCodeSessionGroup("reading", "Reading", listOf("ses_root"), parentID = null),
                    ),
                ),
                client.sessionGroupsHierarchy(it.server),
            )

            val summary = client.sessionGroupsSummary(it.server)
            assertEquals(9L, summary.revision)
            assertEquals(
                OpenCodeSessionGroupSummary("reading", 200, 2, 2, true, OpenCodeSessionGroupOutcomes(succeeded = 1, unreported = 1)),
                summary.groups[0],
            )
            assertEquals(OpenCodeSessionGroupSummary("group_notes", null, 1, 0, false), summary.groups[1])

            assertEquals(
                listOf(
                    "POST /api/rpc/session-groups/capabilities",
                    "POST /api/rpc/session-groups/hierarchyList",
                    "POST /api/rpc/session-groups/hierarchySummary",
                ),
                it.requests.map { r -> r.route },
            )
            for (request in it.requests) assertEquals(json("""{"input":{}}"""), request.json())
        }
    }

    @Test
    fun aLegacyPluginsCapabilitiesHaveNoHierarchy() = runTest {
        val fixture = WireFixture { Reply.Json(200, """{"output":{"apiVersion":2,"operationIDs":true,"mutations":["create","assign"]}}""") }
        withFixture(fixture) {
            val capabilities = OpenCodeClient.liveValue.sessionGroupsCapabilities(it.server)
            assertNull(capabilities.hierarchyVersion)
            assertTrue(!capabilities.supportsHierarchy)
        }
    }

    @Test
    fun aHierarchySnapshotWithoutParentIDsIsRefused() = runTest {
        val fixture = WireFixture { Reply.Json(200, """{"output":{"revision":5,"groups":[{"id":"reading","name":"Reading","sessionIDs":[]}]}}""") }
        withFixture(fixture) {
            expectClientError { OpenCodeClient.liveValue.sessionGroupsHierarchy(it.server) }
        }
    }

    @Test
    fun sessionGroupsHierarchyWritesSendTheContractsBodies() = runTest {
        val answer = """{"output":{"revision":5,"groups":[{"id":"reading","name":"Reading","sessionIDs":["ses_root"],"parentID":null},""" +
            """{"id":"group_papers","name":"Papers","sessionIDs":[],"parentID":"reading"}]}}"""
        withFixture(WireFixture { Reply.Json(200, answer) }) { fixture ->
            val client = OpenCodeClient.liveValue
            val writes = listOf(
                OpenCodeSessionGroupsWrite.HierarchyCreate("Papers", "reading") to """{"name":"Papers","parentID":"reading"}""",
                OpenCodeSessionGroupsWrite.HierarchyCreate("Inbox", null) to """{"name":"Inbox","parentID":null}""",
                OpenCodeSessionGroupsWrite.HierarchyMove("group_papers", null) to """{"groupID":"group_papers","parentID":null}""",
                OpenCodeSessionGroupsWrite.HierarchyMove("group_papers", "reading") to """{"groupID":"group_papers","parentID":"reading"}""",
                OpenCodeSessionGroupsWrite.HierarchyReorder("reading", listOf("group_notes", "group_papers")) to
                    """{"parentID":"reading","groupIDs":["group_notes","group_papers"]}""",
                OpenCodeSessionGroupsWrite.HierarchyReorder(null, listOf("b", "a")) to """{"parentID":null,"groupIDs":["b","a"]}""",
                OpenCodeSessionGroupsWrite.HierarchyAssign("group_papers", "ses_1") to """{"groupID":"group_papers","sessionID":"ses_1"}""",
                OpenCodeSessionGroupsWrite.HierarchyAssign(null, "ses_1") to """{"groupID":null,"sessionID":"ses_1"}""",
                OpenCodeSessionGroupsWrite.HierarchyRename("group_papers", "Notes") to """{"groupID":"group_papers","name":"Notes"}""",
                OpenCodeSessionGroupsWrite.HierarchyRemove("group_papers") to """{"groupID":"group_papers"}""",
            )
            for ((write, input) in writes) {
                val snapshot = client.writeSessionGroups(fixture.server, write, 4, "op-${write.method}")
                assertEquals("the answer is read as the hierarchy", "reading", snapshot.groups.single { g -> g.id == "group_papers" }.parentID)
                val request = fixture.requests.last()
                assertEquals("POST /api/rpc/session-groups/${write.method}", request.route)
                val expected = json(input).jsonObject + mapOf("expectedRevision" to JsonPrimitive(4), "operationID" to JsonPrimitive("op-${write.method}"))
                assertEquals(JsonObject(mapOf("input" to JsonObject(expected))), request.json())
            }
        }
    }

    @Test
    fun aSessionGroupsWriteResendsTheSameOperationAfterALostAnswer() = runTest {
        var calls = 0
        val fixture = WireFixture {
            calls += 1
            if (calls == 1) Reply.Json(502, "") else Reply.Json(200, """{"output":{"revision":1,"groups":[{"id":"g","name":"A","sessionIDs":[]}]}}""")
        }
        withFixture(fixture) {
            withDependenciesSuspending({ this[Clock] = ImmediateClock() }) {
                withRetryPolicy(RetryPolicy(maxAttempts = 2, baseDelay = 1.seconds, maxDelay = 1.seconds, retryWindow = null, waitsForNetwork = false)) {
                    OpenCodeClient.liveValue.writeSessionGroups(it.server, OpenCodeSessionGroupsWrite.Create("A"), 0, "op-1")
                }
            }
            assertEquals(2, it.requests.size)
            assertEquals("the resend is the same write under the same ID", it.requests[0].json(), it.requests[1].json())
        }
    }
}
