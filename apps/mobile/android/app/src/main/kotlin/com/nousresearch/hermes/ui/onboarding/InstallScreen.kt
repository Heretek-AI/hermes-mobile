package com.nousresearch.hermes.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.HermesApi
import com.nousresearch.hermes.HermesInstaller
import com.nousresearch.hermes.R

/**
 * InstallScreen — runs the 8-stage Hermes installer.
 *
 * Subscribes to [HermesApi.installProgress] and renders the
 * current stage with a progress bar. The "Start install" button
 * kicks off [HermesApi.startInstall] (which calls
 * [HermesInstaller.startInstall] internally). On success, the
 * stage emits `Complete` and the screen calls
 * [HermesApi.setAppState] to advance to Setup.
 *
 * Workstream C: when the install fails with an error that looks
 * like a Termux RUN_COMMAND permission denial (substring match
 * on "permission" or "RUN_COMMAND"), surface a guidance card with
 * an "Open App Settings" button alongside the generic Retry —
 * Termux only returns `err != RESULT_OK` for these specific
 * dispatch-level failures, so the heuristic is reliable.
 */
@Composable
fun InstallScreen(hermes: HermesApi) {
    val installProgress by hermes.installProgress.collectAsState(initial = null)
    var doctorOutput by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Auto-start the install on first render; the user already
        // picked a path on the Welcome screen.
        if (!isRunning && installProgress == null) {
            isRunning = true
            try {
                hermes.startInstall()
            } catch (e: Exception) {
                installError = e.message
                isRunning = false
            }
        }
    }

    // Watch the last few stages; when Complete, advance to Setup
    LaunchedEffect(installProgress) {
        val p = installProgress ?: return@LaunchedEffect
        if (p.step == p.totalSteps && p.error == null && p.title.contains("complete", ignoreCase = true)) {
            // Brief pause so the user can see the "Complete" state
            kotlinx.coroutines.delay(800)
            hermes.setAppState(HermesApi.AppState.Setup)
        } else if (p.error != null) {
            isRunning = false
            installError = p.error
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Installing Hermes",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Running 8 install stages. This may take 5-15 minutes on first launch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        installProgress?.let { stage ->
            StageRow(stage = stage, isCurrent = isRunning)
            // Also render the per-stage detail + log tail so the
            // user can see what's happening.
            Spacer(Modifier.height(8.dp))
            Text(
                text = stage.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stage.log.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stage.log.takeLast(400),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (installError != null) {
            Text(
                text = "Install failed: $installError",
                color = MaterialTheme.colorScheme.error,
            )
            if (looksLikeTermuxPermissionError(installError)) {
                Spacer(Modifier.height(8.dp))
                TermuxPermissionNeededCard(
                    onOpenSettings = { hermes.openAppSettings() },
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    installError = null
                    isRunning = true
                    try { hermes.startInstall() } catch (e: Exception) {
                        installError = e.message
                        isRunning = false
                    }
                },
            ) { Text("Retry") }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Doctor output:",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = doctorOutput.ifEmpty { "(will appear after Stage 5)" },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Heuristic: does this install error look like Termux refusing our
 * RUN_COMMAND dispatch because the permission hasn't been granted (or
 * Termux's `allow-external-apps` is off)? Termux's RunCommandService
 * surfaces these via `err != RESULT_OK` with an errmsg that our
 * [com.nousresearch.hermes.TermuxResultReceiver] promotes to the
 * stderr field.
 *
 * Workstream C B6 followup: tightened to avoid false positives on
 * pip's "Permission denied" filesystem errors. We now match only on
 * tokens that are Termux-specific:
 * - "RUN_COMMAND" (verbatim API name in Termux's errmsg)
 * - "allow-external-apps" (Termux property the user must enable)
 * - "plugin_action_disabled" (Termux's actual errmsg key when
 *   allow-external-apps is unset)
 * The bare word "permission" alone is NOT a signal — pip prints it
 * routinely for unrelated FS errors.
 */
private fun looksLikeTermuxPermissionError(error: String?): Boolean {
    if (error == null) return false
    val lower = error.lowercase()
    return "run_command" in lower ||
        "allow-external-apps" in lower ||
        "plugin_action_disabled" in lower
}

@Composable
private fun TermuxPermissionNeededCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.termux_permission_needed_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.termux_permission_needed_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.termux_permission_open_settings))
            }
        }
    }
}

@Composable
private fun StageRow(stage: HermesInstaller.Stage, isCurrent: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${stage.step}/${stage.totalSteps}  ${stage.title}",
            style = MaterialTheme.typography.bodyLarge,
            color = if (isCurrent)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurface,
        )
    }
}
