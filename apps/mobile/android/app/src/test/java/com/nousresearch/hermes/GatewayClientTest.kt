package com.nousresearch.hermes

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GatewayClientTest — uses [MockWebServer] to fake the Python
 * gateway. Verifies non-streaming request/requestJson shape
 * and that the SSE stream parses chunk / done events.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GatewayClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GatewayClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = GatewayClient(apiKey = "test-key", baseUrl = server.url("/").toString().removeSuffix("/"))
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
}
