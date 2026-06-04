package com.nousresearch.hermes

import com.nousresearch.hermes.chat.SseEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GatewayClientTest — uses [MockWebServer] to fake the OpenAI
 * Chat Completions endpoint. Verifies non-streaming
 * request/requestJson shape, that the SSE stream parses
 * chunk/done/error events, and that the request payload is
 * the OpenAI shape.
 */
class GatewayClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GatewayClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = GatewayClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            model = "MiniMax-M3",
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun request_GET_returnsBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val body = client.request("GET", "/v1/test")
        assertEquals("""{"ok":true}""", body)
    }

    @Test
    fun request_POST_sendsJsonBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        client.request("POST", "/v1/test", mapOf("foo" to "bar"))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/test", recorded.path)
        val sent = recorded.body.readUtf8()
        assertTrue(sent.contains("foo"))
        assertTrue(sent.contains("bar"))
    }

    @Test
    fun requestJson_parsesJson() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"a":1,"b":"two"}"""))
        val obj = client.requestJson("GET", "/v1/test")
        assertEquals(1, obj.optInt("a"))
        assertEquals("two", obj.optString("b"))
    }

    @Test
    fun request_returnsEmptyForNon2xx() = runTest {
        // v1 of GatewayClient.request throws on non-2xx; assert
        // an exception is raised. (The caller can catch and
        // surface as a chat error.)
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))
        var threw = false
        try { client.request("GET", "/v1/test") } catch (e: Exception) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun abortChat_isIdempotent() {
        // No active call → no crash
        client.abortChat()
        client.abortChat()
    }

    // ── Phase 8: OpenAI Chat Completions SSE stream ──────────

    @Test
    fun stream_postsToChatCompletionsPath() = runTest {
        // Enqueue an empty stream so the collector finishes
        // without blocking.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        // Drain the flow (limit 1 to avoid collecting the close())
        client.stream(message = "hello").toList()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(
            "expected /chat/completions, got ${recorded.path}",
            recorded.path.endsWith("/chat/completions"),
        )
        val sent = recorded.body.readUtf8()
        assertTrue("payload missing model: $sent", sent.contains("\"model\":\"MiniMax-M3\""))
        assertTrue("payload missing stream:true: $sent", sent.contains("\"stream\":true"))
        assertTrue("payload missing messages: $sent", sent.contains("\"messages\""))
        assertTrue("payload missing user content: $sent", sent.contains("hello"))
    }

    @Test
    fun stream_prependsSystemPrompt() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        client.stream(message = "hi", systemPrompt = "You are Hermes.").toList()
        val sent = server.takeRequest().body.readUtf8()
        assertTrue("system role missing: $sent", sent.contains("\"role\":\"system\""))
        assertTrue("system content missing: $sent", sent.contains("You are Hermes"))
    }

    @Test
    fun stream_parsesChunkFrames() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"id":"cmpl-1","choices":[{"index":0,"delta":{"content":"Hello"}}]}

                    data: {"id":"cmpl-1","choices":[{"index":0,"delta":{"content":" world"}}]}

                    data: {"id":"cmpl-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )
        val events = client.stream(message = "hi").toList()
        // We expect: Chunk("Hello"), Chunk(" world"), Done(sessionId="cmpl-1")
        val chunks = events.filterIsInstance<SseEvent.Chunk>().map { it.content }
        assertEquals(listOf("Hello", " world"), chunks)
        val done = events.filterIsInstance<SseEvent.Done>().firstOrNull()
        assertEquals("cmpl-1", done?.sessionId)
    }

    @Test
    fun stream_parsesErrorFrames() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"error":{"message":"Incorrect API key","type":"auth"}}

                            data: [DONE]

                            """.trimIndent()),
        )
        val events = client.stream(message = "hi").toList()
        val err = events.filterIsInstance<SseEvent.Error>().firstOrNull()
        assertEquals("Incorrect API key", err?.message)
    }

    @Test
    fun stream_surfacesHttpErrors() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"message":"unauthorized"}}"""),
        )
        val events = client.stream(message = "hi").toList()
        val err = events.filterIsInstance<SseEvent.Error>().firstOrNull()
        assertTrue("expected error, got: $events", err != null)
        assertTrue(
            "expected HTTP 401 in error: ${err?.message}",
            err?.message?.contains("401") == true,
        )
    }
}
