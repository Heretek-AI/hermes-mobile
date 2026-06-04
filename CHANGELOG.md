# Changelog

All notable changes to hermes-mobile will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Phase 0 (2026-06) — Cleanup & architectural foundation
- **BREAKING:** WebView+Capacitor+vendored-renderer scaffolding removed.
  The Compose tree is the only client of `HermesApi`.
- Consolidated two parallel Kotlin layers (`android-runner/` →
  `apps/mobile/android/`) with rsync + CI drift-guard.
- `HermesAPIPlugin` reduced to a 130-line no-op stub.
- Architecture documented at `docs/architecture.md`.

### Phase 1 — HermesApi surface expansion
- 44 → ~120 typed methods: file-IO backed (config, env, models,
  soul, profiles, kanban, cron, skills, MCP, memory providers,
  backup, logs, OpenClaw, platform toggles) and gateway-backed
  (transcribe, api-server-key, registry, update flow).
- Type-shape fixes: `getBatteryOptStatus` returns
  `{ignoring:Boolean}`, `getStartOnBoot` returns `{value:Boolean}`,
  `stopVoiceCapture` returns `{mimeType, base64, path}`.
- OAuth deep-link fix: `MainActivity.onNewIntent` routes
  `hermes://oauth-callback` to `handleOAuthCallback` which writes
  the code to `auth.json`.
- New `CronJobEntity` + `CronJobDao` (Room v2 with destructive
  migration from v1).
- Missing event SharedFlows: `oauthLoginProgress`,
  `claw3dSetupProgress`, `updateAvailable`, `updateDownloadProgress`,
  `updateDownloaded`, `updateError`, `menuNewChat`,
  `menuSearchSessions`, `contextMenuCopy`, `contextMenuSelect`,
  `deepLink`, `showMenu`.

### Phase 2 — Install/onboarding flow as Compose
- `AppState` enum + `MutableStateFlow` on `HermesApi`.
- 5 new screens: `SplashScreen`, `WelcomeScreen`, `InstallScreen`,
  `SetupScreen`, `MainScreen`.
- `MainActivity.setContent` switches on `hermes.appState` to
  render the right onboarding screen.
- `HermesApi.init()` inspects `installer.checkInstall().verified`
  and the active profile directory to route to
  Welcome/Setup/Main.

### Phase 3 — Navigation graph 5 → 13 destinations
- New `Destination` sealed class with `BottomTab` (5) +
  `MoreEntry` (12) subtypes.
- 6th "More" tab opens a `ModalBottomSheet` with a 3-column
  grid of the 12 secondary destinations.
- `TopBarWithBack` is the standard top bar for overflow
  destinations; bottom nav hides on those routes.

### Phase 4 — Screen implementations (12 screens)
- Skills, Settings, Soul, Models, Providers, Tools, Schedules,
  Gateway, Discover, Agents, Office, Kanban, Persona.
- Each backs onto the Phase 1 HermesApi methods.

### Phase 5 — Polish
- Voice capture: `ChatViewModel.startVoiceCapture` →
  `stopVoiceCapture` → gateway `transcribeAudio` → chat input.
- Attachments: paperclip button → ACTION_GET_CONTENT →
  `stageAttachment` → chips below the input.
- Context menu: long-press on a chat bubble opens
  `MessageContextMenu` with Copy + Select.
- Deep links: `hermes://chat/<id>` resumes the named session.
- `ACTION_SEND` text-share-sheet pre-fills the chat input.
- OAuth flow reachable from Providers → Add → OAuth.

### Phase 6 — Tests
- `HermesApiContractTest` with 80+ tests covering every Phase 1
  method.
- `GatewayClientTest` with MockWebServer.
- `HermesNavGraphTest` instrumented Compose UI test that
  navigates all 5 bottom-nav tabs + opens the More sheet.
- Test deps added: JUnit 4, MockK, Turbine,
  kotlinx-coroutines-test, MockWebServer, ui-test-junit4.

### Phase 7 — Distribution & polish
- Sentry opt-in via `setCrashReportingEnabled`; DSN read from
  `BuildConfig.SENTRY_DSN` (set from `SENTRY_DSN` env var).
- F-Droid build script + metadata already in place.
- GitHub Actions release pipeline already in place (sign,
  zipalign, generate latest.json, publish release).
- ABI splits already configured (arm64-v8a mandatory,
  armeabi-v7a + x86_64 optional, universal APK for F-Droid).
- Accessibility audit script already in place.
