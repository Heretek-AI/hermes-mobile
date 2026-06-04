# android-runner

The canonical source of truth for the Hermes Mobile Android sources.

## Why this directory exists

Before Phase 0 of the parity plan (see `~/.claude/plans/groovy-fluttering-island.md`),
the same set of Kotlin sources existed in two places: this directory
(`android-runner/app/src/main/kotlin/com/nousresearch/hermes/`) and the
generated Capacitor project at `apps/mobile/android/app/src/main/kotlin/com/nousresearch/hermes/`.
`setup-android.sh` would copy from runner to live on every build, but
the live tree accumulated Compose-specific files (HermesApi.kt,
MainActivity.kt, chat/, db/, memory/, ui/) that the runner didn't have,
and the two trees drifted.

Phase 0c of the plan promotes the runner to the single source of truth
for **all** Kotlin sources. `setup-android.sh` now does a single
`rsync --delete` of `android-runner/app/src/main/kotlin/com/nousresearch/hermes/`
to `apps/mobile/android/app/src/main/kotlin/com/nousresearch/hermes/`,
and `script/check-kotlin-drift.sh` fails CI on any divergence.

## Layout

```
android-runner/
├── README.md                                         # this file
├── app/
│   ├── build.gradle.template                         # the Gradle build for the Hermes app
│   └── src/main/
│       ├── AndroidManifest.xml                       # source of truth for <uses-permission>
│       ├── kotlin/com/nousresearch/hermes/           # all Hermes Kotlin sources
│       │   ├── HermesApi.kt                          # the platform-level Kotlin API
│       │   ├── HermesApp.kt                          # Application; owns HermesApi singleton
│       │   ├── MainActivity.kt                       # ComponentActivity → Compose root
│       │   ├── HermesInstaller.kt                    # 8-stage install (Termux | bundled)
│       │   ├── HermesAPIPlugin.kt                    # no-op Capacitor bridge (Phase 0d)
│       │   ├── GatewayClient.kt                      # HTTPS client for the Python gateway
│       │   ├── GatewaySupervisor.kt                  # gateway subprocess lifecycle
│       │   ├── GatewayForegroundService.kt           # FGS hosting the gateway
│       │   ├── SshTunnelService.kt                   # ssh -N -L forwarder
│       │   ├── BundledPythonRunner.kt                # python-build-standalone extractor
│       │   ├── TermuxProbe.kt                        # detect com.termux install
│       │   ├── TermuxRunner.kt                       # RUN_COMMAND intent bridge
│       │   ├── BootReceiver.kt                       # auto-start on boot
│       │   ├── BatteryOptHelper.kt                   # ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
│       │   ├── OAuthBrowserActivity.kt               # OAuth deep-link target
│       │   ├── NotifChannels.kt                      # notification channel registration
│       │   ├── chat/                                 # Chat types + Room entities
│       │   ├── db/                                   # Room database + DAOs
│       │   ├── memory/                               # Memory types
│       │   └── ui/                                   # Compose UI tree (5-tab nav today)
│       └── res/values/strings.xml                    # source of truth for gateway notif strings
```

## Editing

Edit files **here**, not in `apps/mobile/android/app/src/main/kotlin/com/nousresearch/hermes/`.
The next `bash apps/mobile/scripts/setup-android.sh` will sync your changes
to the live tree. CI's `script/check-kotlin-drift.sh` will fail if the
two trees diverge.

When adding new files: place them in the appropriate subdirectory
under `kotlin/com/nousresearch/hermes/`, then run the sync script. The
rsync will pick them up automatically.

When deleting files: delete them here, then run the sync script. The
`--delete` flag removes them from the live tree too.

## Adding a new Hermes source

1. `android-runner/app/src/main/kotlin/com/nousresearch/hermes/MyNewClass.kt`
2. `bash apps/mobile/scripts/setup-android.sh`
3. `./gradlew :app:assembleDebug`
4. Commit both `android-runner/` and `apps/mobile/android/` (they should
   match after step 2; committing both is the audit trail).
