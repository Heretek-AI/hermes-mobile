package com.nousresearch.hermes.chat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Chat data shapes — Kotlin mirrors of the TypeScript types in
 * `packages/hermes-ipc/src/types.ts` (lines 199-245 and 357-365).
 *
 * These are the value types that flow through the 6
 * [com.nousresearch.hermes.HermesApi] event SharedFlows and the
 * payloads persisted to the local Room database.
 *
 * Phase 2 only needs:
 * - [ChatUsage] — emitted once per turn with token counts
 * - [MessageKind] — discriminator for the 5 message types in
 *   the chat (user / assistant / reasoning / tool_call / tool_result)
 * - [MessageEntity] — the Room row for each chat message
 * - [SessionSummary] — the list-sessions aggregation row
 *
 * The wire format for SSE events from the gateway is intentionally
 * minimal — the desktop's `/v1/chat` endpoint emits one JSON object
 * per `data:` line with a `type` field. We parse them into the
 * [SseEvent] sealed class and project the fields into the
 * SharedFlow + Room row.
 */
data class ChatUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cost: Double? = null,
    val rateLimitRemaining: Int? = null,
    val rateLimitReset: Long? = null,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
)

/** The 5 message kinds. Mirrors `SessionMessage.kind` in the
 *  TypeScript IPC. Stored as a plain String in the DB column
 *  (the enum's `name`); use [fromString] / [name] for the
 *  round-trip. Room doesn't need a TypeConverter when the
 *  Kotlin field type is already a String. */
enum class MessageKind {
    USER,
    ASSISTANT,
    REASONING,
    TOOL_CALL,
    TOOL_RESULT;

    companion object {
        fun fromString(s: String): MessageKind = when (s.lowercase()) {
            "user" -> USER
            "assistant" -> ASSISTANT
            "reasoning" -> REASONING
            "tool_call" -> TOOL_CALL
            "tool_result" -> TOOL_RESULT
            else -> ASSISTANT
        }
    }
}

/**
 * One chat message persisted to the local DB. The shape mirrors
 * the desktop's `SessionMessage` discriminated union — the
 * [kind] column is the discriminator and the [content]/[text]/
 * [args]/[callId]/[name] columns are filled in for the
 * appropriate kind.
 *
 * `parentAssistantId` is non-null for [MessageKind.REASONING]
 * and [MessageKind.TOOL_CALL] rows (the assistant message that
 * owns them) and null for [MessageKind.USER] / [MessageKind.ASSISTANT]
 * / [MessageKind.TOOL_RESULT] rows.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "kind")
    val kind: String = "assistant",
    @ColumnInfo(name = "role")
    val role: String? = null,
    @ColumnInfo(name = "content")
    val content: String? = null,
    @ColumnInfo(name = "text")
    val text: String? = null,
    @ColumnInfo(name = "args")
    val args: String? = null,
    @ColumnInfo(name = "call_id")
    val callId: String? = null,
    @ColumnInfo(name = "name")
    val name: String? = null,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "parent_assistant_id")
    val parentAssistantId: Long? = null,
) {
    /** Convenience: the [MessageKind] enum view of [kind]. */
    val kindEnum: MessageKind get() = MessageKind.fromString(kind)
}

/** Aggregate row from the `listSessions` query. Maps to the
 *  TypeScript `SessionSummary` interface. */
data class SessionSummary(
    val id: String,
    val source: String,
    val startedAt: Long,
    val endedAt: Long?,
    val messageCount: Int,
    val model: String,
    val title: String?,
    val preview: String,
)

/** One parsed `data:` line from the gateway's SSE stream.
 *  The gateway emits a JSON object per event; we project the
 *  `type` field to the sealed-class variant. */
sealed class SseEvent {
    data class Chunk(val content: String) : SseEvent()
    data class Reasoning(val text: String) : SseEvent()
    data class ToolProgress(val tool: String) : SseEvent()
    data class Usage(val usage: ChatUsage) : SseEvent()
    data class Done(val sessionId: String?) : SseEvent()
    data class Error(val message: String) : SseEvent()
    data class Other(val type: String, val raw: String) : SseEvent()
}
