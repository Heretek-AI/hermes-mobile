# F-Droid distribution

F-Droid builds the APK from source using their own toolchain and
signing key. This directory holds the metadata and reproducible
build script.

## Submitting to F-Droid

1. Fork https://gitlab.com/fdroid/fdroiddata
2. Copy `f-droid/metadata/com.nousresearch.hermes.yml` into the
   fork's `metadata/` directory
3. Open a merge request. F-Droid's CI runs `f-droid/build.sh`
   from this repo to verify the build is reproducible.
4. Once merged, the app appears in the F-Droid client within a
   day or two.

## Why F-Droid works for us

- All deps in the renderer are MIT/Apache 2.0. `AllowedNonFreeLibraries`
  is empty in the metadata.
- Sentry is opt-in and NOT bundled for F-Droid builds. The
  Sentry SDK has a closed-source component that would otherwise
  violate F-Droid's allowed-nonfree list.
- No Google Play Services. The gateway has zero Firebase deps
  and no GMS-leveraging paths in the renderer.
- The renderer is pure-web — no React Native, no native UI
  components — so F-Droid's reproducible-build toolchain
  produces a byte-identical APK across runs.

## Reproducible build verification

F-Droid's CI runs `f-droid/build.sh` in a clean container with
pinned versions:
- NDK r27d (LTS; matches the gradle.properties `ndkVersion`)
- Gradle 8.7
- OpenJDK 21
- Node 22
- pnpm 9

The build is reproducible if `sha256sum` of the produced APK is
identical across runs. We enable `--no-build-cache` and `--scan`
to avoid the cache layer that adds timestamps.

## App-ID rotation policy

The `com.nousresearch.hermes` application ID is permanent. We
do not plan to change it. If we ever need to (e.g. F-Droid key
compromise), the recovery is a new application ID with a
migration helper in the renderer that prompts users to install
the new app and re-imports their data.
