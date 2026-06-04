# Python bundling alternatives for hermes-mobile

**Status:** decision document — synthesizes research done for the
`atomic-wondering-sunrise` plan's Workstream C, after the user chose
"Research alternatives before committing" to Chaquopy.
**Date:** 2026-06-04

---

## The headline finding

The Workstream B caveat I flagged in code (`runShellViaTermux` doesn't
get real exit codes back) is **a tractable engineering follow-up, not a
fundamental Termux limitation**. Termux's `RUN_COMMAND_PENDING_INTENT`
extra is officially documented, has been working since Termux 0.109,
and lets us receive `STDOUT`, `STDERR`, `EXIT_CODE`, and `ERRMSG` back
as a Bundle in a `BroadcastReceiver`. Wiring it up in `TermuxRunner.kt`
is ~1–2 days of work.

That changes the whole calculus. The Chaquopy workstream was sized
against "Termux is the only working path but it doesn't work
end-to-end either" — but Termux *can* work end-to-end with a small
follow-up. Chaquopy is no longer the obvious bundled-Python answer,
and may not be needed at all in v1.

---

## What we evaluated

Three agents researched, in parallel, the realistic 2026 alternatives
to Chaquopy. Findings condensed below; full reports in the planning
session transcript.

### Option 1 — Bundle Termux's `bootstrap.zip` as a private chroot

- **License:** Aggregate redistribution of `bash` (GPL-3),
  `coreutils` (GPL-3), `proot` (GPL-2), plus permissive
  (openssl Apache-2, python PSF-2, libffi/zlib/etc. MIT/BSD) is
  legally permitted. Requires a `THIRD_PARTY_LICENSES.txt` ship-along.
- **APK delta:** `bootstrap-aarch64.zip` is 30.86 MB compressed
  (~120 MB extracted). Plus ~15–25 MB of `hermes-agent[termux-all]`
  wheels. APK grows ~50 MB.
- **Killer:** every binary inside Termux's bootstrap bakes the
  prefix `/data/data/com.termux/files/usr` into shebangs and (some)
  RUNPATH entries. Extracting to a different app's `filesDir` requires
  rewriting ~5,000 shebangs at install time and verifying RUNPATH with
  `readelf` per release. Doable but adds a regression risk on every
  Termux release.
- **No precedent:** AnLinux, UserLAnd, and every other "Linux on
  Android" app builds their own rootfs rather than bundling Termux's.
- **Verdict:** mechanically possible, strategically wrong. The cost of
  PREFIX rewriting > the cost of asking the user to install Termux
  (which Workstream A's F-Droid deep-link already makes one tap away).

### Option 2 — Alpine minirootfs + proot (downloaded first run)

- **Size:** `alpine-minirootfs-3.23.4-aarch64.tar.gz` is ~3–4 MB
  compressed; `apk add python3 py3-pip` brings it to ~30 MB; plus
  `pip install hermes-agent[termux-all]` brings on-device footprint
  to ~100–140 MB. **All on-device, not in the APK.**
- **License:** ship `proot` (GPL-2) as a separate binary we shell out
  to (not link); Alpine packages are permissive; hermes-agent is MIT.
  Clean.
- **APK delta:** 0 MB if downloaded on first run (recommended).
- **C-extension wheels:** Alpine uses musl libc, and `cryptography`,
  `pydantic-core`, `psutil`, `uvloop`, `brotlicffi` **all publish
  `musllinux_aarch64` wheels** to PyPI. So `pip install` works
  end-to-end with no recipe maintenance.
- **Engineering:** 2–4 engineer-weeks for a polished install (rootfs
  builder, NDK-compiled `proot` binary, Kotlin extractor + progress
  UI, gateway lifecycle, update/repair flows).
- **F-Droid:** F-Droid allows GPL'd binaries (proot is fine). Large
  first-run download is a UX issue, not a policy issue.
- **Verdict:** the proper "no Termux required" alternative to
  Chaquopy. License-clean and zero-cost on APK size.

### Option 3 — Python 3.13+ official Android (PEP 738) + cibuildwheel

- **PEP 738 ("Adding Android as a supported platform")** is **Final**,
  authored by Malcolm Smith of Chaquopy. Android is **Tier 3** in
  PEP 11 — Russell Keith-Magee and Petr Viktorin as contacts.
- **Embeddable tarballs:** `python.org` publishes them since 3.14.0
  (Oct 2025). 3.14.5 is the current stable. **3.13.x has no official
  Android tarball.**
- **Wheel ecosystem:** `cibuildwheel --platform android` works for
  3.13+ and 3.14+. PyPI/pip understand the `android_<api>_<abi>` tag.
  **But:** none of cryptography / pydantic-core / uvloop / psutil /
  brotlicffi currently publish `android_*` wheels to PyPI. The
  Chaquopy mirror (`https://chaquo.com/pypi-13.1/`) remains the
  de-facto curated source for these.
- **`python-build-standalone`** (astral-sh) **does not ship Android
  targets** as of 2026-06. The `aarch64-unknown-linux-gnu` artifact
  is glibc-targeted and does not load on bionic.
- **Verdict:** the interpreter half is solved; the dep ecosystem half
  is not. Without Chaquopy's wheel mirror, you'd have to maintain a
  cibuildwheel recipe for each native dep and pin versions in our
  repo. That is real recurring engineering cost.

### Option 4 — Termux companion-app pattern (status quo + RESULT_INTENT)

- **Architecture:** keep using Termux as the runtime; talk to it via
  `RUN_COMMAND` with `EXTRA_PENDING_INTENT`. Termux's own apt-managed
  Python is the interpreter; Termux's pip + manylinux/musllinux
  patches handle the wheel ecosystem.
- **RUN_COMMAND_PENDING_INTENT** is documented at
  [github.com/termux/termux-app/wiki/RUN_COMMAND-Intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).
  Result bundle keys: `EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT`,
  `..._STDERR`, `..._EXIT_CODE`, `..._ERR`, `..._ERRMSG`. Combined
  stdout+stderr cap is ~100 KB; original lengths in `*_ORIGINAL_LENGTH`.
- **Permissions:** user grants `com.termux.permission.RUN_COMMAND` in
  Android Settings → App Info → Additional Permissions, and
  Termux's `~/.termux/termux.properties` needs
  `allow-external-apps=true`. The F-Droid build of Termux defaults
  this on; manual setup is a one-line edit.
- **Source of truth:** Termux's `RunCommandService.java`
  ([raw](https://raw.githubusercontent.com/termux/termux-app/master/app/src/main/java/com/termux/app/RunCommandService.java))
  reads `EXTRA_PENDING_INTENT` and forwards as `TermuxService`'s own
  `EXTRA_PENDING_INTENT`. Constants live in MIT-licensed
  `termux-shared` (`TermuxConstants.java`).
- **APK delta:** zero.
- **License:** zero contamination (we shell out via intent, no
  linking).
- **F-Droid:** Termux is on F-Droid; our Workstream A deep-link
  already makes installing it one tap.
- **Engineering:** ~1–2 days to extend `TermuxRunner` with
  `PendingIntent.getBroadcast` + a `BroadcastReceiver` that
  resumes a coroutine `Continuation` with the result bundle.
- **Trade-off:** requires Termux to be installed on the device.
  Workstream A made this trivial.
- **Verdict:** **the best path for v1.** Cheap, clean, F-Droid
  friendly, no APK bloat, full functionality.

### Dead ends

- **PyOxidizer**: no Android target; project on life support since 2022.
- **Pyodide / WebAssembly Python**: 5–20× slower for `pydantic-core`-
  heavy paths; inverts hermes-agent's architecture (gateway becomes
  a WebView-hosted server bridged to localhost).
- **RustPython**: no ports of cryptography / pydantic-core / uvloop.
- **`proot-rs` as an embeddable library**: no `cdylib` / `libproot.so`
  exists — proot in any flavor is a CLI binary, not a linkable
  library.
- **`sharedUserId="com.termux"`**: deprecated in Android 10, removed
  in later versions. Cannot be used to share Termux's UID.

---

## Decision matrix

Scored 1–5 (5 = best); composite = (license × 2) + APK + effort +
F-Droid + functionality.

| Option | License | APK | Effort | F-Droid | Functionality | Total |
|---|---|---|---|---|---|---|
| **4. Termux companion + RESULT_INTENT** | 5 | 5 | 5 | 5 | 5 | **30** |
| **2. Alpine rootfs + proot (1st-run download)** | 4 | 4 | 2 | 4 | 4 | 22 |
| **Chaquopy Business** | 3 (paid, MIT-compatible) | 3 | 4 | 2 (separate F-Droid build) | 5 | 20 |
| **3. PEP 738 + cibuildwheel recipes** | 5 | 4 | 1 (recurring) | 4 | 3 (wheel gaps) | 21 |
| **1. Bundle Termux bootstrap** | 4 | 2 | 1 | 3 | 4 | 18 |
| Chaquopy Standard (GPL-3) | 2 | 3 | 4 | 2 | 5 | 17 |

---

## Recommendation

**Tier 1 (v1, immediate): Termux companion + RESULT_INTENT (Option 4).**
Finish what Workstream B started by wiring up
`RUN_COMMAND_PENDING_INTENT` in `TermuxRunner.kt`. Update
`HermesInstaller` to await the result bundle on each shell stage.
Estimated effort: 1–2 days. End state: Termux install path works
end-to-end with real exit codes and output capture; Workstream A's
F-Droid deep-link gets every non-Termux user one tap away from a
working setup. Ship as v1.

**Tier 2 (v2, post-launch if demand exists): Alpine rootfs + proot
(Option 2).** Only build if user telemetry / feedback shows a
meaningful population that wants hermes-agent on Android *without*
installing Termux. Engineering cost is real (2–4 weeks) but
license-clean, F-Droid friendly, and gives a true "single APK runs
everything" UX with a one-time first-run download.

**Tier 3 (probably never): Chaquopy.** Reconsider only if Alpine+proot
proves unworkable for some specific reason. The license cost
(Business tier $) and F-Droid friction (separate build variant)
remain real; Alpine+proot dominates Chaquopy on license/F-Droid/APK
dimensions even at higher engineering cost.

**Path dropped: Bundle Termux bootstrap (Option 1).** Strategically
wrong — the PREFIX-rewriting tax is permanent and the user-facing
benefit (no Termux app on device) is already captured by Tier 2.

---

## What this changes in the original plan

The `atomic-wondering-sunrise.md` plan's Workstream C (Chaquopy
integration) is **superseded** by this analysis. The actual next
work is a small extension to Workstream B:

**Workstream B-followup — wire `RUN_COMMAND_PENDING_INTENT`** so
`runShellViaTermux` is sync-with-exit-code-and-output:

1. Extend `TermuxRunner.run(...)` to accept an optional
   `onResult: (RunResult) -> Unit` callback. Internally, build a
   `PendingIntent.getBroadcast(ctx, requestCode, resultIntent,
   PendingIntent.FLAG_MUTABLE | FLAG_UPDATE_CURRENT)` and attach it
   as `EXTRA_PENDING_INTENT` on the dispatched Intent. Register a
   one-shot `BroadcastReceiver` keyed by `requestCode` that unpacks
   the result bundle and invokes `onResult`.
2. Add `suspend fun runAndWait(...)` that wraps the callback in
   `suspendCancellableCoroutine`. Returns a full `RunResult` with
   exit code, stdout, stderr.
3. Update `HermesInstaller.runShellViaTermux` to use `runAndWait`.
   Each stage now returns the real exit code.
4. Update `HermesInstaller.applyPatches` to use `TermuxRunner` instead
   of raw `ProcessBuilder("git", ...)` (which never worked since git
   isn't on the host app's PATH).
5. Documentation: a short README section on the two Termux
   permissions the user grants on first install (`RUN_COMMAND`
   permission + `allow-external-apps` properties file).

Estimated effort: **1–2 days**. End state: hermes-mobile + Termux is
a fully working install path end-to-end. Workstreams A + B + B-followup
together close out the entire "Termux installation works" goal.

---

## Sources

Termux RUN_COMMAND PendingIntent:
- https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- https://raw.githubusercontent.com/termux/termux-app/master/app/src/main/java/com/termux/app/RunCommandService.java
- https://raw.githubusercontent.com/termux/termux-app/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java

Termux bootstrap:
- https://api.github.com/repos/termux/termux-packages/releases/latest (bootstrap-2026.05.31-r1+apt.android-7, aarch64 = 30.86 MB)
- https://raw.githubusercontent.com/termux/termux-packages/master/LICENSE.md
- https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxInstaller.java

PEP 738 / CPython on Android:
- https://peps.python.org/pep-0738/
- https://peps.python.org/pep-0011/
- https://www.python.org/downloads/android/
- https://docs.python.org/3/using/android.html
- https://cibuildwheel.readthedocs.io/en/stable/platforms/

Alpine Linux:
- https://alpinelinux.org/downloads/

proot (license + lack of embeddable library):
- https://github.com/proot-me/proot (GPL-2.0+)
- https://github.com/proot-me/proot-rs (CLI only)
- https://github.com/termux/proot-distro

Astral python-build-standalone:
- https://github.com/astral-sh/python-build-standalone/releases

Linux-userland precedents:
- https://github.com/EXALAB/AnLinux-App
- https://github.com/CypherpunkArmory/UserLAnd
- https://github.com/termux/termux-boot
- https://github.com/termux/termux-api

Pyodide / WebAssembly Python (negative finding):
- https://pyodide.org/en/stable/
- https://pyodide.org/en/stable/usage/faq.html
- https://rustpython.github.io/

PyOxidizer (negative finding):
- https://github.com/indygreg/PyOxidizer
