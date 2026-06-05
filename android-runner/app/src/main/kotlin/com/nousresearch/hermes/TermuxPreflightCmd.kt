package com.nousresearch.hermes

/**
 * Shared constants for the Termux preflight wizard.
 *
 * Lives in its own file (not nested inside [HermesApi] or
 * [com.nousresearch.hermes.ui.onboarding.TermuxPreflightScreen]) so
 * that both the auto-set path in [HermesApi.ensureAllowExternalApps]
 * and the manual-display path in
 * [com.nousresearch.hermes.ui.onboarding.TermuxPreflightScreen] share
 * one source of truth. Editing the one-liner in this file updates
 * both the silent auto-set and the user-visible copy command, so
 * the two paths can never drift.
 */
object TermuxPreflightCmd {

    /**
     * Idempotent edit of `~/.termux/termux.properties` that sets
     * `allow-external-apps=true` (creating the file/dir if missing,
     * rewriting the key if present-but-wrong, no-op if already
     * correct). Designed so the user can paste it into a Termux
     * shell as-is. The same string is dispatched verbatim by
     * [HermesApi.ensureAllowExternalApps] via
     * [TermuxRunner.runAndWait].
     *
     * Note: this is a `val`, not a `const val`, because the bash
     * `$file` / `$key` / `$value` / `$newline` interpolations
     * would otherwise be interpreted as Kotlin string templates.
     * Every `$` is escaped as `${'$'}` so the resulting Kotlin
     * string contains literal bash dollar signs when dispatched.
     */
    val SET_ALLOW_EXTERNAL_APPS_CMD: String =
        """value="true"; key="allow-external-apps"; file="/data/data/com.termux/files/home/.termux/termux.properties"; mkdir -p "${'$'}(dirname "${'$'}file")"; chmod 700 "${'$'}(dirname "${'$'}file")"; if ! grep -E '^'"${'$'}key"'=.*' ${'$'}file &>/dev/null; then [[ -s "${'$'}file" && ! -z "${'$'}(tail -c 1 "${'$'}file")" ]] && newline=${'$'}'\n' || newline=""; echo "${'$'}newline${'$'}key=${'$'}value" >> "${'$'}file"; else sed -i'' -E 's/^'"${'$'}key"'=.*/'"${'$'}key=${'$'}value"'/' ${'$'}file; fi"""

    /**
     * The official one-line installer docs page, surfaced as the
     * "escape hatch" install path in the wizard's final step.
     * The hash anchors the page to the termux section's first
     * option so the user lands on the relevant content.
     */
    const val ONE_LINE_DOCS_URL: String =
        "https://hermes-agent.nousresearch.com/docs/getting-started/termux#option-1-one-line-installer"

    /**
     * Termux install URLs — duplicated from
     * [com.nousresearch.hermes.ui.onboarding.WelcomeScreen] to
     * avoid widening that file's import surface. Both deep-links
     * are stable F-Droid / GitHub Releases URLs; if either ever
     * moves, update both locations.
     */
    const val F_DROID_TERMUX_URL: String = "https://f-droid.org/packages/com.termux/"
    const val GITHUB_TERMUX_URL: String = "https://github.com/termux/termux-app/releases"
}
