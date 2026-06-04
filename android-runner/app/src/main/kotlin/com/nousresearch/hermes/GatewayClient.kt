package com.nousresearch.hermes

import android.util.Log
import com.nousresearch.hermes.chat.ChatUsage
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
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * GatewayClient — opens an SSE connection to the user's
 * hermes-agent gateway and parses the `data:` event stream.
 *
 * The desktop's gateway exposes `/v1/chat` as an
 * `text/event-stream` endpoint that emits one JSON object per
 * `data:` line. The desktop's `src/main/chat-client.ts` reads
 * the stream and surfaces `onChunk` / `onReasoning` / `onUsage` /
 * `onDone` / `onError` events to the React renderer. Phase A's
 * [HermesApi] does the same shape: it owns 6 SharedFlows, and
 * the [GatewayClient.stream] function emits the parsed events
 * as a [Flow] of [SseEvent] for the API to project.
 *
 * ## Why okhttp
 *
 * `androidx.lifecycle:lifecycle-service:2.8.7` pulls in
 * `okhttp3:4.12.0` transitively (for the WorkManager
 * `ListenableWorker` API). The chat client reuses that — no
 * extra dep, no plugin.
 *
 * ## abortChat
 *
 * The okhttp `Call` is cancellable. [abortChat] calls
 * `call.cancel()` from any thread, which trips the
 * `IOException("Canceled")` in the SSE reader and unwinds the
 * coroutine. The GatewaySupervisor's gateway process is left
 * alone — `abortChat` only stops the HTTP request; the user
 * gateway is killed via `stopGateway`.
 */
class GatewayClient(
    private val apiKey: String,
    private val baseUrl: String = "http://127.0.0.1:8642",
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
     * Cold flow — the HTTP request is only issued on the first
     * collect, and cancellation propagates to the okhttp `Call`.
     *
     * Caller is expected to:
     * 1. `takeWhile { it !is SseEvent.Done && it !is SseEvent.Error }`
     *    to bound the stream, OR
     * 2. call [abortChat] from a separate coroutine to break out.
     */
    fun stream(
        message: String,
        profile: String = "default",
        resumeSessionId: String? = null,
        history: List<Pair<String, String>> = emptyList(),
    ): Flow<SseEvent> = callbackFlow<SseEvent> {
        val payload = JSONObject().apply {
            put("message", message)
            put("profile", profile)
            if (resumeSessionId != null) put("resume_session_id", resumeSessionId)
            put(
                "history",
                org.json.JSONArray().apply {
                    history.forEach { (role, content) ->
                        val obj = JSONObject()
                        obj.put("role", role)
                        obj.put("content", content)
                        put(obj)
                    }
                },
            )
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/v1/chat")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Cache-Control", "no-cache")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        activeCall = call
        // The plugin's HermesApi owns the lifetime; if the
        // collector's coroutine is cancelled, cancel the call.
        invokeOnClose { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                if (call.isCanceled()) {
                    // AbortChat: don't surface the synthetic
                    // "Canceled" IOException as a user-visible
                    // chat error. Just close the flow.
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
                                "gateway returned HTTP ${r.code}: " +
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

    /**
     * Cancel the in-flight chat request. Safe to call from any
     * thread; idempotent.
     */
    fun abortChat() {
        val c = activeCall ?: return
        c.cancel()
        activeCall = null
    }

    private fun parseSseStream(source: BufferedSource, onEvent: (SseEvent) -> Unit) {
        // SSE wire format: blank-line-delimited records. Each
        // record is a sequence of `field: value` lines followed
        // by a blank line. We care about the `data:` field (the
        // gateway only emits `data:` and `event:` lines, and the
        // event type is in the JSON payload, not the SSE event
        // field).
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
                // ignore other fields (id:, event:, retry:, comments)
            }
        }
    }

    private fun parseSseData(data: String): SseEvent {
        val obj = try {
            JSONObject(data)
        } catch (e: Exception) {
            return SseEvent.Other(type = "unparseable", raw = data.take(200))
        }
        return when (val type = obj.optString("type")) {
            "chunk" -> SseEvent.Chunk(content = obj.optString("content"))
            "reasoning" -> SseEvent.Reasoning(text = obj.optString("text"))
            "tool_progress" -> SseEvent.ToolProgress(tool = obj.optString("tool"))
            "usage" -> SseEvent.Usage(
                usage = ChatUsage(
                    promptTokens = obj.optInt("promptTokens"),
                    completionTokens = obj.optInt("completionTokens"),
                    totalTokens = obj.optInt("totalTokens"),
                    cost = obj.opt("cost")?.let { (it as? Number)?.toDouble() },
                    rateLimitRemaining = obj.opt("rateLimitRemaining")?.let { (it as? Number)?.toInt() },
                    rateLimitReset = obj.opt("rateLimitReset")?.let { (it as? Number)?.toLong() },
                    cacheReadTokens = obj.opt("cacheReadTokens")?.let { (it as? Number)?.toInt() },
                    cacheWriteTokens = obj.opt("cacheWriteTokens")?.let { (it as? Number)?.toInt() },
                ),
            )
            "done" -> SseEvent.Done(sessionId = obj.optString("sessionId").takeIf { it.isNotEmpty() })
            "error" -> SseEvent.Error(message = obj.optString("message"))
            else -> SseEvent.Other(type = type, raw = data.take(200))
        }
    }

    companion object {
        private const val TAG = "GatewayClient"
    }
}
