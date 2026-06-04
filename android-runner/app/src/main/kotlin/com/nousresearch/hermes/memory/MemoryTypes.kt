package com.nousresearch.hermes.memory

/**
 * Memory data shapes — Kotlin mirrors of the TypeScript types in
 * `packages/hermes-ipc/src/types.ts` (lines 416-425).
 *
 * The desktop's `~/.hermes/profiles/default/memory.md` is a plain
 * markdown file with one entry per `## heading` section. We mirror
 * that file format on Android so a `hermes-agent` install can
 * read/write the same file via the IPC surface — the desktop's
 * CLI and the mobile Compose UI both touch the same path.
 */
data class MemoryReadResult(
    val memory: MemoryContent,
    val user: MemoryContent,
    val stats: MemoryStats,
)

data class MemoryContent(
    val content: String,
    val exists: Boolean,
    val lastModified: Long?,
)

data class MemoryStats(
    val totalSessions: Int,
    val totalMessages: Int,
)

/** Result of an add / update call — `success = false` carries an
 *  error message in `error`. */
data class MemoryWriteResult(
    val success: Boolean,
    val error: String? = null,
)

/** A single `## heading\nbody\n` section of the memory file.
 *  Returned by [parseEntries] for the list view. */
data class MemoryEntry(
    val index: Int,
    val heading: String,
    val body: String,
)
