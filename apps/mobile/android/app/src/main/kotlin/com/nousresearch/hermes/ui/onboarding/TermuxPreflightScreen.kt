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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nousresearch.hermes.R
import com.nousresearch.hermes.TermuxPreflightCmd
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TermuxPreflightScreen — a 5-step wizard that runs after the user
 * picks "Install locally (Termux)" on the Welcome screen and before
 * the 8-stage installer takes over. It walks the user through:
 *
 *  1. Installing Termux from F-Droid
 *  2. Granting `com.termux.permission.RUN_COMMAND` in Android Settings
 *  3. Enabling `allow-external-apps=true` in `~/.termux/termux.properties`
 *  4. Verifying the property stuck
 *  5. Picking an install path (in-app 8-stage or one-line)
 *
 * The wizard auto-attempts every device-side step it can and falls
 * back to copyable commands / Settings deep-links when it can't, so
 * a first-time user is never blocked by the chicken-and-egg (the
 * very `allow-external-apps` property we need to dispatch commands
 * is the one that's blocking the dispatch until we set it).
 *
 * Reuse-heavy: every visual primitive (cards, monospace text, the
 * "Step N of M" progress bar) is borrowed from existing screens —
 * see the [StepHeader], [SuccessCard], and [CommandCopyCard]
 * private composables below.
 */
@Composable
fun TermuxPreflightScreen(hermes: HermesApi) {
    var currentStep by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.preflight_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.preflight_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val stepTitles = listOf(
            stringResource(R.string.preflight_step1_title),
            stringResource(R.string.preflight_step2_title),
            stringResource(R.string.preflight_step3_title),
            stringResource(R.string.preflight_step4_title),
            stringResource(R.string.preflight_step5_title),
        )

        StepHeader(
            currentStep = currentStep,
            totalSteps = stepTitles.size,
            title = stepTitles.getOrNull(currentStep) ?: "",
        )

        when (currentStep) {
            0 -> Step1InstallTermux(hermes) { currentStep = 1 }
            1 -> Step2GrantRunCommand(hermes) { currentStep = 2 }
            2 -> Step3AllowExternal(hermes) { currentStep = 3 }
            3 -> Step4Verify(
                hermes = hermes,
                onContinue = { currentStep = 4 },
                onBack = { currentStep = 2 },
            )
            4 -> Step5InstallHermes(
                hermes = hermes,
                onInstall = { hermes.setAppState(HermesApi.AppState.Installing) },
                onBack = { currentStep = 3 },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Step 1: Install Termux
// ---------------------------------------------------------------------------

@Composable
private fun Step1InstallTermux(hermes: HermesApi, onContinue: () -> Unit) {
    var status by remember { mutableStateOf(hermes.getTermuxStatus()) }
    var waiting by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }

    // Poll for Termux installation for up to 30s after the user has
    // been bounced to F-Droid. The pattern mirrors the
    // while(true) { ...; delay(1000) } loop in GatewayScreen.kt:42-50.
    LaunchedEffect(waiting) {
        if (!waiting) return@LaunchedEffect
        timedOut = false
        val detected = withTimeoutOrNull(30_000L) {
            while (true) {
                val s = hermes.getTermuxStatus()
                status = s
                if (s.installed) return@withTimeoutOrNull true
                delay(1_000)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        if (detected == null) timedOut = true
        waiting = false
    }

    if (status.installed) {
        SuccessCard(
            title = stringResource(R.string.preflight_step1_body_installed, status.version ?: "?"),
            body = null,
            onContinue = onContinue,
        )
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.preflight_step1_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.preflight_step1_body_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    hermes.openExternal(TermuxPreflightCmd.F_DROID_TERMUX_URL)
                    waiting = true
                },
            ) { Text(stringResource(R.string.termux_install_action)) }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { hermes.openExternal(TermuxPreflightCmd.GITHUB_TERMUX_URL) },
            ) { Text(stringResource(R.string.termux_install_github)) }
            if (waiting) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.preflight_step1_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (timedOut) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.preflight_step1_timeout),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { waiting = true }) {
                    Text(stringResource(R.string.preflight_step1_check_again))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 2: Grant com.termux.permission.RUN_COMMAND
// ---------------------------------------------------------------------------

@Composable
private fun Step2GrantRunCommand(hermes: HermesApi, onContinue: () -> Unit) {
    var granted by remember { mutableStateOf(hermes.hasTermuxRunCommandPermission()) }
    var recheck by remember { mutableStateOf(0) }

    // Auto-poll for the permission grant for up to 60s. The
    // recheck state increments on "I did it" taps to force an
    // immediate re-read.
    LaunchedEffect(recheck) {
        if (granted) return@LaunchedEffect
        withTimeoutOrNull(60_000L) {
            while (!granted) {
                delay(1_000)
                if (hermes.hasTermuxRunCommandPermission()) {
                    granted = true
                    return@withTimeoutOrNull
                }
            }
        }
    }

    if (granted) {
        SuccessCard(
            title = stringResource(R.string.preflight_step2_body_verified),
            body = null,
            onContinue = onContinue,
        )
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.preflight_step2_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.preflight_step2_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { hermes.openAppSettings() }) {
                Text(stringResource(R.string.termux_permission_open_settings))
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { recheck++ }) {
                Text(stringResource(R.string.preflight_action_i_did_it))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 3: Allow external apps (auto-set, then manual fallback)
// ---------------------------------------------------------------------------

/** Step 3 internal phase. Drives the three possible UI shapes:
 *  [Attempting] while the auto-set RUN_COMMAND is in flight,
 *  [ManualNeeded] after auto-set is rejected (chicken-and-egg),
 *  and [AwaitingManual] while we poll for the user's manual paste. */
private enum class Step3Phase { Attempting, ManualNeeded, AwaitingManual }

@Composable
private fun Step3AllowExternal(hermes: HermesApi, onContinue: () -> Unit) {
    var phase by remember { mutableStateOf(Step3Phase.Attempting) }
    var hardError by remember { mutableStateOf<String?>(null) }
    var recheck by remember { mutableStateOf(0) }

    // Auto-set attempt: fires once on entry. If it succeeds (exit
    // code 0 AND follow-up verify reads "true"), advance. If it
    // returns a chicken-and-egg signal (plugin_action_disabled in
    // stderr), flip to manual mode. Any other failure is a hard
    // error.
    LaunchedEffect(Unit) {
        if (phase != Step3Phase.Attempting) return@LaunchedEffect
        val result = hermes.ensureAllowExternalApps()
        val stderr = result.stderr.lowercase()
        if (result.exitCode == 0) {
            val v = hermes.getTermuxProperty("allow-external-apps")
            if (v == "true") {
                onContinue()
                return@LaunchedEffect
            }
        }
        if ("plugin_action_disabled" in stderr ||
            "allow-external-apps" in stderr
        ) {
            phase = Step3Phase.ManualNeeded
        } else if ("not installed" in stderr || "permission" in stderr) {
            hardError = result.stderr.ifBlank { "Termux rejected the command" }
            phase = Step3Phase.ManualNeeded
        } else {
            // Unknown failure — fall back to manual so the user
            // isn't stuck. They can still copy/paste the command.
            phase = Step3Phase.ManualNeeded
        }
    }

    // While in ManualNeeded / AwaitingManual, poll the property
    // for up to 60s and auto-advance when it flips to "true".
    LaunchedEffect(phase, recheck) {
        if (phase == Step3Phase.Attempting) return@LaunchedEffect
        phase = Step3Phase.AwaitingManual
        withTimeoutOrNull(60_000L) {
            while (true) {
                val v = hermes.getTermuxProperty("allow-external-apps")
                if (v == "true") {
                    onContinue()
                    return@withTimeoutOrNull
                }
                delay(1_000)
            }
        }
    }

    when (phase) {
        Step3Phase.Attempting -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.preflight_step3_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.preflight_step3_body_auto),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Step3Phase.ManualNeeded,
        Step3Phase.AwaitingManual -> {
            CommandCopyCard(
                command = TermuxPreflightCmd.SET_ALLOW_EXTERNAL_APPS_CMD,
                caption = stringResource(R.string.preflight_step3_caption),
                hardError = hardError,
                onCheckAgain = { recheck++ },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Step 4: Verify the property stuck
// ---------------------------------------------------------------------------

@Composable
private fun Step4Verify(
    hermes: HermesApi,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    var verified by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Brief delay so the user sees the "Verifying..." state.
        delay(800)
        val v = hermes.getTermuxProperty("allow-external-apps")
        if (v == "true") verified = true
    }

    if (verified) {
        SuccessCard(
            title = stringResource(R.string.preflight_step4_body_verified),
            body = null,
            onContinue = onContinue,
        )
        return
    }

    // Failure state — render the errorContainer card with a
    // "back to step 3" button.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.preflight_step4_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.preflight_step4_error_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack) {
                Text(stringResource(R.string.preflight_action_back_to_step_3))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 5: Pick install path (8-stage or one-line)
// ---------------------------------------------------------------------------

@Composable
private fun Step5InstallHermes(
    hermes: HermesApi,
    onInstall: () -> Unit,
    onBack: () -> Unit,
) {
    var showWhy by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.preflight_step5_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.preflight_step5_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onInstall,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.preflight_action_run_in_app))
                }
                OutlinedButton(
                    onClick = { hermes.openExternal(TermuxPreflightCmd.ONE_LINE_DOCS_URL) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.preflight_action_run_one_liner))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { showWhy = !showWhy }) {
                    Text(stringResource(R.string.preflight_action_why_both))
                }
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.preflight_action_back))
                }
            }
            if (showWhy) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.preflight_why_both_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private composables
// ---------------------------------------------------------------------------

/** "Step N of M" header + linear progress indicator. Pattern
 *  borrowed from the live-install progress UI in InstallScreen.kt:124. */
@Composable
private fun StepHeader(currentStep: Int, totalSteps: Int, title: String) {
    Column {
        Text(
            text = stringResource(R.string.preflight_step_label, currentStep + 1, totalSteps),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (currentStep + 1).toFloat() / totalSteps.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** Green-checked success card. Mirrors the `primaryContainer`
 *  `InstallOptionCard` shape in WelcomeScreen.kt:132-170, with a
 *  [title], optional [body] paragraph, and a Continue button. */
@Composable
private fun SuccessCard(title: String, body: String?, onContinue: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "✓ $title",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (body != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onContinue) {
                Text(stringResource(R.string.preflight_action_continue))
            }
        }
    }
}

/** Copyable command card. Renders the [command] in a monospace
 *  font (style borrowed from InstallScreen.kt:115-119), with a
 *  Copy button that flips to "Copied" for 1.5s. The [onCheckAgain]
 *  is a re-read trigger for the wizard's poll-and-wait loop.
 *  [hardError] surfaces a non-recoverable error if the auto-set
 *  failed for a non-chicken-and-egg reason. */
@Composable
private fun CommandCopyCard(
    command: String,
    caption: String,
    hardError: String? = null,
    onCheckAgain: () -> Unit = {},
) {
    var copied by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardLabel = "termux.properties"

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (hardError != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.preflight_step3_error_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = hardError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.preflight_step3_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cb.setPrimaryClip(android.content.ClipData.newPlainText(clipboardLabel, command))
                            copied = true
                        },
                    ) {
                        Text(
                            text = stringResource(
                                if (copied) R.string.preflight_action_copied
                                else R.string.preflight_action_copy_command
                            ),
                        )
                    }
                    TextButton(onClick = onCheckAgain) {
                        Text(stringResource(R.string.preflight_action_i_did_it))
                    }
                }
            }
        }
    }

    // Reset the "Copied" label after 1.5s.
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_500)
        copied = false
    }
}

/** Error card. Mirrors the `errorContainer` shape of
 *  `TermuxPermissionNeededCard` in InstallScreen.kt:190-216. */
@Composable
private fun ErrorCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
