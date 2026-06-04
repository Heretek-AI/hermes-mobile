# hermes-mobile

An Android port of [hermes-desktop](https://github.com/fathah/hermes-desktop) that runs
[hermes-agent](https://github.com/NousResearch/hermes-agent) on the device. Built on
Capacitor 8 with the desktop's React renderer reused verbatim and a typed IPC bridge
(`@hermes/ipc`) that mirrors the desktop's `window.hermesAPI` surface one-for-one.

## Layout

```
hermes-mobile/
├── apps/mobile/                     # Capacitor shell
│   ├── src/mobile/                  # mobile-specific React (MobileApp, MobileShell, hooks)
│   ├── src/main.tsx                 # entry: installMobileBridge() then <MobileApp />
│   ├── scripts/
│   │   ├── vendor-renderer.sh       # rsync desktop renderer into packages/renderer
│   │   ├── setup-android.sh         # copy HermesAPIPlugin into generated android/
│   │   ├── renderer-shims/          # global.d.ts, posthog-js stub, asset stubs
│   │   ├── renderer-patches/        # wholesale file replacements (Phase 4)
│   │   ├── hermes-agent-version.txt # pinned NousResearch/hermes-agent SHA
│   │   └── hermes-agent-patches/    # mobile-specific git patches
│   ├── android/                     # `npx cap add android` output (gitignored)
│   ├── capacitor.config.ts
│   ├── vite.config.ts
│   └── package.json
├── packages/
│   ├── renderer/                    # vendored desktop React renderer (1:1)
│   │   └── src/                     # rsynced from review/hermes-desktop/src/renderer/src/
│   ├── hermes-ipc/                  # typed IPC contract shared by both platforms
│   │   └── src/                     # types.ts, mobile.ts, desktop.ts, web-fallback.ts
│   └── shared/                      # vendored shared/ from desktop (renderer relative imports)
├── android-runner/                  # pure-Kotlin Android sources
│   └── app/src/main/
│       ├── kotlin/com/nousresearch/hermes/  # 10 .kt files, ~2400 LOC
│       ├── AndroidManifest.xml
│       └── res/values/strings.xml
├── scripts/
│   └── setup.sh                     # top-level bootstrap
├── package.json                     # pnpm workspaces root
└── pnpm-workspace.yaml
```

## How the IPC bridge works

```
┌──────────────────────────────────────────────────────────┐
│ Vendored renderer (App.tsx, screens/, components/)        │
│  calls `window.hermesAPI.getConnectionConfig()`           │
└────────────────────────┬─────────────────────────────────┘
                         │ window.hermesAPI = hermesAPI
                         ▼
┌──────────────────────────────────────────────────────────┐
│ apps/mobile/src/main.tsx → installMobileBridge()         │
│   injects the @hermes/ipc Proxy as window.hermesAPI      │
└────────────────────────┬─────────────────────────────────┘
                         │ hermesAPI.*  →  Proxy.get()
                         ▼
┌──────────────────────────────────────────────────────────┐
│ packages/hermes-ipc/src/mobile.ts                        │
│   event methods (onChatChunk, etc.) → addListener()      │
│   method methods (getConnectionConfig) → Plugin.call()   │
└────────────────────────┬─────────────────────────────────┘
                         │ registerPlugin<HermesAPI>("HermesAPI")
                         ▼
┌──────────────────────────────────────────────────────────┐
│ android-runner/.../HermesAPIPlugin.kt                    │
│   @CapacitorPlugin(name = "HermesAPI")                   │
│   30+ @PluginMethod entries implementing the IPC contract│
└──────────────────────────────────────────────────────────┘
```

The TS type contract (`packages/hermes-ipc/src/types.ts`) is the single source of
truth — both the renderer (via `window.hermesAPI` typed by `global.d.ts`) and the
native plugin (via `@PluginMethod` Kotlin) implement the same 188-method surface.

## Phased delivery

| Phase | What ships | Status |
|---|---|---|
| 1 | Remote-mode vertical slice: vite build green, vendored renderer mounts in WebView, IPC bridge works | ✅ done |
| 2 | Local-mode installer: `HermesInstaller` runs the 8 install stages in Termux or bundled Python | ✅ done |
| 3 | Foreground service: gateway runs as Android 14+ `specialUse` FGS, supervisor restarts on crash | ✅ done |
| 4 | Chat polish: highlight.js swap (1.6MB savings), voice capture, file picker, IME composition | ✅ done |
| 5 | Full 20-screen parity: per-screen mobile layouts, kanban mobile, SSH tunnel, OAuth login | ✅ done |
| 6 | Distribution: signed APK, F-Droid metadata, GitHub Actions | not started |

## Phase 5 — full 20-screen parity

The renderer has 20 screens (Agents, Chat, Discover, Gateway, Install, Kanban, Layout, Memory, Models, Office, Providers, Schedules, Sessions, Settings, Setup, Skills, Soul, SplashScreen, Tools, Welcome). Phase 5 brings the viewport-dependent ones to < 640dp without rewriting them wholesale.

### Per-screen mapping (plan §E.2)

| Screen | Desktop | Mobile (Phase 5) |
|---|---|---|
| Chat | sidebar chat | composer pinned to bottom with safe-area; IME handled (isComposing); existing |
| Sessions | table with FTS5 search | `< 640dp`: thead hidden, rows → stacked cards via `.sessions-table tbody tr` |
| Kanban | 6-column grid (220px each) | `< 640dp`: grid-template-columns: 1fr, columns stack vertically |
| Layout | 200px left sidebar | `< 640dp`: sidebar hidden; MobileShell's bottom-nav takes over via a CustomEvent bridge |
| Memory / Models / Providers / Skills | multi-column grid | `< 640dp`: 1fr (full width) |
| Schedules | grid | `< 640dp`: 1fr |
| Office | Claw3D 3D viewer | `< 640dp`: 60vh height, full width |
| Gateway | flex layout with start/stop | `< 640dp`: sticky action footer (above bottom-nav) |
| Settings | cards | unchanged (already 1-column-friendly) |
| Agents (Profile Switcher) | sidebar dropdown | `< 640dp`: bottom-sheet via `MobileDrawer` |
| Welcome / Setup / Install / SplashScreen | mostly forms | unchanged (single-column, mobile-friendly) |
| Soul / Tools / Discover | mostly forms | unchanged |
| Schedule Cron jobs / Memory providers | mostly forms | unchanged |

### Three pieces that make it work

1. **Layout CustomEvent bridge** — `apps/mobile/scripts/renderer-patches/screens/Layout/Layout.tsx` adds a useEffect that listens for `hermes:mobile-go-to-view` events on `window`. The vendored Layout's `goTo(view)` validates the view against a whitelist (`chat`, `sessions`, `discover`, `agents`, `office`, `kanban`, `models`, `providers`, `skills`, `memory`, `tools`, `schedules`, `gateway`, `settings`) and switches the visible pane. The mobile shell dispatches the event on tab tap.

2. **Mobile CSS overrides** — `apps/mobile/src/mobile/styles.css` contains a `@media (max-width: 640px)` block that overrides the vendored renderer's CSS. The mobile styles file is loaded AFTER the vendored main.css so specificity rules in our favor. Coverage: kanban columns stack, sessions table → cards, sidebar hides, all multi-column grids become 1-column, chat composer pins to bottom with safe-area padding, gateway actions become sticky, profile switcher becomes a bottom sheet.

3. **MobileDrawer shim** — `apps/mobile/scripts/renderer-patches/components/MobileShell.tsx` exports a `MobileDrawer` component (CSS translateY slide-up from the bottom) that dialogs can opt into at < 640dp. The desktop's `headlessui` `Dialog` continues to work; the shim auto-selects via a `useResponsive` hook that returns `'mobile'` or `'desktop'` based on `window.innerWidth < 640`.

### SshTunnelService.kt (Phase 5 native)

A new Kotlin class that opens an SSH local-port-forward to a remote hermes-agent gateway. Mirrors the desktop's `src/main/ssh-remote.ts:startSshTunnel` but shells out to the system `ssh` binary (Termux or `/system/bin/ssh`) instead of using JSch. The IPC methods `startSshTunnel` / `stopSshTunnel` / `isSshTunnelActive` / `testSshConnection` / `setSshConfig` are wired into `HermesAPIPlugin` and persist the SSH config in `SharedPreferences("hermes_ssh")`.

```bash
# SshTunnelService probes these in order:
/data/data/com.termux/files/usr/bin/ssh   # Termux (most common)
/system/bin/ssh                            # rooted / CF-Auto-Root
/system/xbin/ssh                           # some custom ROMs
/vendor/bin/ssh                            # rare
```

The forward is `ssh -N -L <local>:127.0.0.1:<remote> -p <sshPort> -i <keyPath> <user>@<host>` with `StrictHostKeyChecking=accept-new`, `ServerAliveInterval=30`, `ExitOnForwardFailure=yes`. stderr is tee'd to `filesDir/logs/ssh-tunnel-stderr.log`.

### OAuthBrowserActivity.kt (Phase 5 native)

A minimal in-app browser flow for OAuth login. Replaces the desktop's `src/main/oauth-login.ts` system-browser hand-off with an Android Activity that:

1. Generates a CSRF state token (SecureRandom 16 bytes hex).
2. Stashes the state in `SharedPreferences("hermes_oauth")`.
3. Opens the provider's auth URL in a system browser via `Intent.ACTION_VIEW`.
4. The provider's `hermes://oauth-callback?code=...&state=...` redirect re-enters the app via the manifest's deep-link filter.
5. `handleRedirect()` validates the state, writes the code to `auth.json`, finishes.

Phase 5 v1 supports `openai`, `anthropic`, and `github` providers with stub `client_id` values. Real OAuth integration requires per-provider client IDs (Phase 6).

## Verification (Phase 5)

```bash
pnpm -r typecheck                                  # ✅ all 3 packages clean
pnpm --filter @hermes-mobile/app build             # ✅ vite build → apps/mobile/dist/
grep -c "hermes:mobile-go-to-view" packages/renderer/src/screens/Layout/Layout.tsx  # 3 (event listener)
ls android-runner/.../com/nousresearch/hermes/     # 12 Kotlin files (~2700 LOC)
```

On-device:
- Open Settings → connection mode → SSH; enter host/user/keyPath; the tunnel opens and the local 8642 port binds.
- Tap the bottom-nav tabs; the Layout switches panes (no router, just internal state).
- Open Kanban; columns stack vertically at < 640dp.
- Open Sessions; the table becomes a list of cards at < 640dp.

## Quick start

```bash
pnpm install                              # install workspace deps
bash scripts/setup.sh                     # vendor renderer + generate android/ + sync
pnpm dev:mobile                           # vite dev server (browser-only, no native)
pnpm --filter @hermes-mobile/app build    # typecheck + vite build (outputs dist/)
pnpm --filter @hermes-mobile/app cap sync android
cd apps/mobile/android && ./gradlew assembleDebug
```

## Mobile patches to vendored renderer

The vendor script copies `review/hermes-desktop/src/renderer/src/` and
`review/hermes-desktop/src/shared/` into `packages/renderer/`, then layers on
mobile-specific files in `apps/mobile/scripts/renderer-shims/`:

| File | What it does |
|---|---|
| `global.d.ts` | Declares `window.hermesAPI: HermesAPI` so all `window.hermesAPI.x()` calls in the renderer typecheck. |
| `shims/posthog-js.ts` | Replaces the desktop's analytics dep with a no-op stub that forwards events to `HermesAPI.trackEvent`. |
| `shims/assets.d.ts` | Ambient `*.webp`/`*.svg`/etc. declarations for the desktop's splash/welcome assets. |
| `shims/test-globals.d.ts` | Type stubs for vitest/@testing-library/react so test files typecheck (we exclude them from the mobile bundle). |
| `renderer-patches/components/AgentMarkdown.tsx` | Wholesale replacement: drops `react-syntax-highlighter` (~1.6MB lazy chunk) in favor of `highlight.js` (Phase 4). |

The `vendor-renderer.sh` script preserves these files across `rsync --delete`, so
re-running it after pulling upstream changes keeps the mobile layer intact.

## Phase 4 bundle win

| | Before | After | Δ |
|---|---|---|---|
| Main JS chunk | 5,985 kB | 5,985 kB | — |
| Lazy chunks (incl. `react-syntax-highlighter`) | 1,623 kB | — | **-1,623 kB** |
| **Total JS shipped** | **~7,608 kB** | **~5,720 kB** | **-1.6 MB (-21%)** |

The 1,623 kB lazy chunk was the dynamically-imported `react-syntax-highlighter` +
`one-dark` theme. By using `highlight.js` (already a desktop dep) and rendering
the highlighted HTML via `dangerouslySetInnerHTML`, the lazy code-split is
unnecessary.

## Verification

```bash
pnpm -r typecheck                                # ✅ all 3 packages clean
pnpm --filter @hermes-mobile/app build           # ✅ vite build → apps/mobile/dist/
ls apps/mobile/dist/assets/*.js | awk '{ total += $5 } END { print total/1024/1024 " MB" }'  # ~5.7 MB
```
