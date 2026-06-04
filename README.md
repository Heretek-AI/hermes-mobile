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
| 5 | Full 20-screen parity: per-screen mobile layouts, kanban mobile, SSH tunnel | not started |
| 6 | Distribution: signed APK, F-Droid metadata, GitHub Actions | not started |

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
