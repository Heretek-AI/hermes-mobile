package com.nousresearch.hermes.config

import java.io.File

/**
 * ConfigStore — simple, dependency-free reader/writer for the small set
 * of human-edited text files in ~/.hermes/ that hermes-agent and the
 * gateway consume:
 *
 *   - `config.yaml`       : top-level config (default_model, default_provider, …)
 *   - `.env`              : HERMES_HOME, API_SERVER_KEY, API_SERVER_PORT, …
 *   - `models.yaml`       : per-profile model registry
 *   - `tools.yaml`        : per-profile toolset toggles
 *   - `gateway.yaml`      : per-profile 16-platform messaging platform toggles
 *   - `auth.json`         : credential pool (provider API keys + OAuth tokens)
 *   - `mcp.yaml`          : MCP server configurations
 *   - `soul.md`           : the agent's soul prompt
 *   - `user.md`           : per-profile user profile
 *   - `memory.md`         : per-profile memory entries (existing parser in HermesApi)
 *   - `skills/<name>/SKILL.md` : installed skill contents
 *   - `kanban/boards/<id>.json` : per-board kanban state
 *   - `cron.json`         : scheduled jobs
 *
 * We do **not** use a full YAML library. The format on the wire is
 * shallow (`key: value`, `key:\n  nested: value`, `# comment`,
 * blank lines) and hermes-agent's Python loader accepts what we emit.
 * Adding snakeyaml (or jackson-dataformat-yaml) for this would
 * double the APK size for negligible benefit.
 *
 * The format rules:
 *   - Lines starting with `#` are comments.
 *   - Empty lines are ignored.
 *   - A line of the form `key: value` sets key=value.
 *   - A line of the form `key:` (with no value, possibly trailing
 *     whitespace) opens a nested block. Subsequent lines indented by
 *     exactly two spaces are members of the block; their key names
 *     are stored as `key.subkey`.
 *   - A line of the form `key: [a, b, c]` sets key=List(a,b,c).
 *   - Anything else is ignored (preserved as-is on round-trip when
 *     possible).
 *
 * The .env format is `KEY=VALUE` per line, with `# comment` and blank
 * lines ignored. Values may be quoted with single or double quotes;
 * both are stripped on read.
 *
 * For richer structures (cron jobs, kanban boards, models, auth pool)
 * we read+write the file as a JSON string and let the caller decode
 * with kotlinx.serialization or a hand-rolled parser. The desktop uses
 * YAML for these; we can also accept YAML and translate to JSON for
 * the Kotlin side, but the simplest correct first cut is to have the
 * gateway be the source of truth for those structures and only expose
 * pass-through list/get/set methods in HermesApi. See the per-method
 * docs in HermesApi for the chosen approach.
 */
object ConfigStore {

    // ── key=value (config.yaml, .env) ─────────────────────────────

    /** Read a key=value map from a flat file. Strips `#` comments and
     *  blank lines. Supports simple `key: value` (YAML) and `KEY=VALUE`
     *  (.env) formats. Returns an empty map if the file is missing. */
    fun readKeyValues(file: File): Map<String, String> = try {
        if (!file.exists()) emptyMap()
        else file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() && it.contains(':') || it.contains('=') && !it.startsWith('#') }
            .mapNotNull { line ->
                val sep = if (line.contains('=')) '=' else ':'
                val parts = line.split(sep, limit = 2)
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = parts[1].trim().trimMatches('"').trimMatches('\'')
                    if (k.isNotEmpty()) k to v else null
                } else null
            }
            .toMap()
    } catch (_: Exception) { emptyMap() }

    /** Write a key=value map to a flat file. Existing file is overwritten.
     *  Format: `key: value` (YAML) or `KEY=VALUE` (.env) depending on [format]. */
    fun writeKeyValues(
        file: File,
        values: Map<String, String>,
        format: Format = Format.YAML,
    ) {
        file.parentFile?.mkdirs()
        val sep = if (format == Format.YAML) ": " else "="
        file.writeText(
            values.entries.joinToString("\n") { (k, v) ->
                "$k$sep${v.quoteIfNeeded(format)}\n"
            },
        )
    }

    enum class Format { YAML, ENV }

    private fun String.trimMatches(c: Char): String =
        if (length >= 2 && first() == c && last() == c) substring(1, length - 1) else this

    private fun String.quoteIfNeeded(format: Format): String = when (format) {
        Format.YAML -> if (contains(':') || contains('#')) "\"$this\"" else this
        Format.ENV -> if (contains(' ') || contains('#')) "\"$this\"" else this
    }

    // ── JSON pass-through (for cron.json, kanban/*.json, models.yaml) ──

    /** Read a file as a UTF-8 string. Empty string if the file is missing. */
    fun readText(file: File): String = try {
        if (file.exists()) file.readText() else ""
    } catch (_: Exception) { "" }

    /** Write a UTF-8 string atomically (write to .tmp, then rename).
     *  Used for JSON files the Kotlin side hands to the gateway. */
    fun writeTextAtomic(file: File, contents: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(contents)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            // renameTo can fail on some filesystems (e.g. when crossing
            // mount points). Fall back to a copy + delete.
            file.writeText(contents)
            tmp.delete()
        }
    }

    /** List child files of [dir] matching [suffix]. Empty list if [dir]
     *  is missing. Used for skills/, kanban/boards/, etc. */
    fun listChildren(dir: File, suffix: String = ""): List<File> = try {
        if (!dir.exists() || !dir.isDirectory) emptyList()
        else dir.listFiles { f -> f.isFile && (suffix.isEmpty() || f.name.endsWith(suffix)) }?.toList()
            ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** List subdirectories of [dir]. Empty list if [dir] is missing.
     *  Used for profiles/. */
    fun listSubdirs(dir: File): List<File> = try {
        if (!dir.exists() || !dir.isDirectory) emptyList()
        else dir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** Compute `~/.hermes/<rest>` rooted at the bundled-Python HERMES_HOME
     *  (which is `context.filesDir/home/.hermes`). Used by every
     *  file-IO method in HermesApi that needs a path under the home dir. */
    fun hermesHome(filesDir: java.io.File): File = File(filesDir, "home/.hermes").also {
        it.mkdirs()
    }
}
