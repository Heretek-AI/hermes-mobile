# Changelog

All notable changes to hermes-mobile will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-06-04

First public release. The 16-week desktop-parity plan at
`~/.claude/plans/groovy-fluttering-island.md` lands as a single
shipped artefact. Summary by phase:

- **Phase 0 — Cleanup & architectural foundation:** WebView +
  Capacitor + vendored renderer scaffolding removed; Compose is
  the only UI runtime. `android-runner/` is the canonical Kotlin
  source; CI drift-guard enforces the mirror to
  `apps/mobile/android/`. `HermesAPIPlugin` reduced to a 130-line
  no-op stub. Architecture documented at `docs/architecture.md`.
- **Phase 1 — HermesApi surface expansion:** 44 → 152 typed
  methods across 12 Kotlin files; file-IO-backed config/env/
  models/soul/profiles/kanban/cron/skills/MCP/backup/logs paths
  + gateway-backed transcribe/api-server-key/registry/update paths.
  Type-shape fixes for `getBatteryOptStatus` /
  `getStartOnBoot` / `stopVoiceCapture`. OAuth deep-link
  routing via `MainActivity.onNewIntent`. New `CronJobEntity`
  + `CronJobDao` (Room v2).
- **Phase 2 — Onboarding flow:** `AppState` enum + flow on
  `HermesApi`; 5 Compose screens (Splash / Welcome / Install /
  Setup / Main) with 8-stage install progress + log tail.
- **Phase 3 — Navigation:** 5 bottom-nav tabs + 12 overflow
  destinations = 14 routes. `ModalBottomSheet` for the More tab.
- **Phase 4 — Screen implementations:** Skills, Settings, Soul,
  Models, Providers, Tools, Schedules, Gateway, Discover, Agents,
  Office, Kanban, Persona. (Dashboard remains a `PlaceholderScreen`
  stub.)
- **Phase 5 — Polish:** Voice capture (mic button →
  MediaRecorder → transcribeAudio), attachments (paperclip →
  ACTION_GET_CONTENT → chips), long-press context menu
  (Copy / Select), `hermes://chat/<id>` + `hermes://skill/<name>`
  deep-link routing through `MainActivity.handleIntent` and
  the Compose `NavHostController`, `ACTION_SEND` text-share-sheet
  pre-fills the chat input.
- **Phase 6 — Tests:** `HermesApiContractTest` (80+ tests),
  `GatewayClientTest` (MockWebServer), `HermesNavGraphTest`
  (instrumented).
- **Phase 7 — Distribution & polish:** Real `SentryAndroid.init`
  in `HermesApp.maybeInitSentry()` (gated on the user toggle +
  non-empty `BuildConfig.SENTRY_DSN`); Sentry 8.43.1 dep.
  Release signing config fails fast when `keystore.properties`
  is missing (no more silent unsigned APKs). F-Droid
  `build.sh` + metadata. GitHub Actions release pipeline
  (signed APKs + `latest.json` + draft GitHub Release +
  F-Droid artifact) on tag push (`mobile-v*`).
- **Phase 8 — Chat E2E:** `GatewayClient` speaks OpenAI Chat
  Completions at `POST /chat/completions`; defaults to
  `MiniMax-M3` + `https://api.minimax.io/v1`; model and base
  URL are read from `~/.hermes/config.yaml` so the user can
  swap models without rebuilding. Welcome → Setup → Main
  flow lands the user on a working chat.

### Release artefacts

- Signed universal + per-ABI APKs (GitHub Release, draft).
- Unsigned universal APK (F-Droid rebuild).
- `latest.json` for the in-app updater.

### Known v0.1.0 limitations (deferred to v0.2.0)

- Skill detail screen: `hermes://skill/<name>` lands on the
  Skills tab but doesn't open a detail view yet.
- iOS port, Play Store AAB, Bundled uv install, Hindi / PT
  i18n, gateway auto-restart, Claw3D on mobile, multi-window,
  Wear OS — see `~/.claude/plans/groovy-fluttering-island.md`
  §"Open items deferred to v2".

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
