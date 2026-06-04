package com.nousresearch.hermes

import android.util.Log
import com.nousresearch.hermes.chat.SseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * GatewayClient — opens an SSE connection to an OpenAI-compatible
 * Chat Completions endpoint and parses the `data:` event stream.
 *
 * ## Phase 8: Direct OpenAI Chat Completions
 *
 * The Android app talks directly to an OpenAI-compatible API
 * (default: `https://api.minimax.io/v1`, model `MiniMax-M3`).
 * This bypasses the Hermes Python gateway; chat goes straight
 * from `HermesApi.sendMessage` to the upstream provider.
 *
 * Wire format (POST `/v1/chat/completions`):
 * ```json
 * { "model": "...", "stream": true, "messages": [
 *   { "role": "system", "content": "..." },
 *   { "role": "user",   "content": "..." }
 * ]}
 * ```
 *
 * SSE response (one JSON object per `data:` line, terminated by
 * `data: [DONE]`):
 * ```
 * data: {"id":"…","choices":[{"index":0,"delta":{"content":"Hi"}}]}
 * data: {"id":"…","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
 * data: [DONE]
 * ```
 *
 * The desktop (`review/hermes-desktop/src/main/hermes.ts:579, 700`)
 * uses the same wire format. The Hermes Python gateway's
 * `api_server.py` also exposes `/v1/chat/completions` as the
 * canonical chat endpoint.
 *
 * ## Why okhttp
 *
 * `androidx.lifecycle:lifecycle-service:2.8.7` pulls in
 * `okhttp3:4.12.0` transitively. The chat client reuses that —
 * no extra dep, no plugin.
 *
 * SECURITY: do not add an HttpLoggingInterceptor that logs
 * headers — the bearer token would land in logcat.
 *
 * ## abortChat
 *
 * The okhttp `Call` is cancellable. [abortChat] calls
 * `call.cancel()` from any thread, which trips the
 * `IOException("Canceled")` in the SSE reader and unwinds the
 * coroutine.
 *
 * // legacy: the Phase A-7 protocol spoke a custom Hermes shape
 * (`{type: chunk|reasoning|tool_progress|usage|done|error,…}`)
 * at `/v1/chat`. The Compose UI consumes `SseEvent.Chunk` /
 * `SseEvent.Done` / `SseEvent.Error` / `SseEvent.Other`; the
 * mapping below preserves those variants so the rest of the app
 * is unchanged.
 */
class GatewayClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.minimax.io/v1",
    private val model: String = "MiniMax-M3",
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE: no read deadline
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var activeCall: Call? = null

    /**
     * Open the SSE stream and emit parsed events as a [Flow].
     */
    fun stream(
        message: String,
        profile: String = "default",
        systemPrompt: String? = null,
        history: List<Pair<String, String>> = emptyList(),
    ): Flow<SseEvent> = callbackFlow<SseEvent> {
        val messages = JSONArray()
        if (!systemPrompt.isNullOrBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
        history.forEach { (role, content) ->
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", message)
        })

        val payload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", messages)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Cache-Control", "no-cache")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCall = call
        invokeOnClose { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                if (call.isCanceled()) {
                    close()
                } else {
                    trySend(SseEvent.Error("network error: ${e.message}"))
                    close(e)
                }
            }

            override fun onResponse(c: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        trySend(
                            SseEvent.Error(
                                "HTTP ${r.code}: " +
                                    (r.body?.string()?.take(200) ?: ""),
                            ),
                        )
                        close()
                        return
                    }
                    val source: BufferedSource = r.body?.source()
                        ?: run {
                            trySend(SseEvent.Error("empty response body"))
                            close()
                            return
                        }
                    parseSseStream(source) { ev ->
                        trySend(ev)
                    }
                    close()
                }
            }
        })
    }.flowOn(Dispatchers.IO)

    fun abortChat() {
        val c = activeCall ?: return
        c.cancel()
        activeCall = null
    }

    /**
     * Non-streaming HTTP request helper.
     */
    suspend fun request(
        method: String,
        path: String,
        body: Map<String, Any?> = emptyMap(),
    ): String = suspendCancellableCoroutine { cont ->
        val payload = if (method == "GET" || body.isEmpty()) ""
        else JSONObject(body).toString()
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
        val req = when (method.uppercase()) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(payload.toRequestBody("application/json".toMediaType())).build()
            "PUT" -> builder.put(payload.toRequestBody("application/json".toMediaType())).build()
            "DELETE" -> builder.delete().build()
            else -> builder.post(payload.toRequestBody("application/json".toMediaType())).build()
        }
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                if (call.isCanceled()) cont.resumeWithException(IOException("request cancelled"))
                else cont.resumeWithException(e)
            }
            override fun onResponse(c: Call, response: Response) {
                response.use { r ->
                    val text = r.body?.string() ?: ""
                    if (!r.isSuccessful) {
                        cont.resumeWithException(
                            IOException("HTTP ${r.code}: ${text.take(200)}"),
                        )
                    } else {
                        cont.resume(text)
                    }
                }
            }
        })
    }

    suspend fun requestJson(
        method: String,
        path: String,
        body: Map<String, Any?> = emptyMap(),
    ): JSONObject {
        val text = request(method, path, body)
        return try { JSONObject(text) } catch (_: Exception) { JSONObject() }
    }

    private fun parseSseStream(source: BufferedSource, onEvent: (SseEvent) -> Unit) {
        var dataBuffer: StringBuilder? = null
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    val data = dataBuffer?.toString() ?: continue
                    dataBuffer = null
                    if (data.isBlank() || data == "[DONE]") continue
                    onEvent(parseSseData(data))
                }
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").trim()
                    if (dataBuffer == null) dataBuffer = StringBuilder()
                    dataBuffer!!.append(payload)
                }
            }
        }
    }

    private fun parseSseData(data: String): SseEvent {
        val obj = try {
            JSONObject(data)
        } catch (e: Exception) {
            return SseEvent.Other(type = "unparseable", raw = data.take(200))
        }
        // OpenAI error frame
        obj.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "upstream error")
            return SseEvent.Error(message = msg)
        }
        // OpenAI choice chunk
        val choices = obj.optJSONArray("choices")
            ?: return SseEvent.Other(type = "no-choices", raw = data.take(200))
        if (choices.length() == 0) {
            return SseEvent.Other(type = "empty-choices", raw = data.take(200))
        }
        val first = choices.optJSONObject(0)
            ?: return SseEvent.Other(type = "no-first-choice", raw = data.take(200))
        val delta = first.optJSONObject("delta")
        val content = delta?.optString("content").orEmpty()
        val finishReason = first.optString("finish_reason", "")
        return when {
            content.isNotEmpty() -> SseEvent.Chunk(content = content)
            finishReason == "stop" || finishReason == "length" || finishReason == "tool_calls" ->
                SseEvent.Done(sessionId = obj.optString("id").takeIf { it.isNotEmpty() })
            else -> SseEvent.Other(type = "empty-delta", raw = data.take(200))
        }
    }

    companion object {
        private const val TAG = "GatewayClient"
    }
}
