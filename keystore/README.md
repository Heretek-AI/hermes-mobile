# Release signing keystore

This directory holds the RSA 4096 release key for `com.nousresearch.hermes`.
The `release.jks` is **gitignored** (it's a private secret); the
`keystore.properties.template` is checked in so contributors know what
env vars to set on their local machine and on CI.

## First-time generation (one-time, by the maintainer)

```bash
# 25 years = 9125 days. RSA 4096. Stored at ./keystore/release.jks.
keytool -genkey -v \
  -keystore keystore/release.jks \
  -alias hermes \
  -keyalg RSA -keysize 4096 \
  -validity 9125 \
  -dname "CN=Hermes Agent, OU=Mobile, O=Nous Research, L=San Francisco, S=California, C=US"
```

The keytool prompts for a password — use a strong one and store it in
the maintainer's password manager (1Password / LastPass / Bitwarden).
The same password is used for both the keystore and the key alias.

## Local dev setup

```bash
cp keystore/keystore.properties.template keystore/keystore.properties
# Edit keystore.properties to fill in storePassword, keyPassword,
# and (optionally) a different storeFile path.
```

The Gradle `signingConfig` reads `keystore.properties` from the
project root (where `apps/mobile/android/` is). The
`build.gradle` adds a guard that fails fast if the file is missing
in release mode.

## CI setup (GitHub Actions)

Store the following in the repo's encrypted secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_FILE` | base64 of `keystore/release.jks` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `hermes` (the alias used at keytool) |
| `KEY_PASSWORD` | the key password (often the same as keystore) |

The `mobile-build.yml` workflow decodes `KEYSTORE_FILE` to a temp
file, exports the passwords as env vars, and `./gradlew assembleRelease`
picks them up via `signingConfigs.release.storeFile` etc.

## Rotation policy

The 25-year validity is intentional — rotation is a multi-month
process that involves re-signing every published artifact, updating
Play App Signing (if/when we ship there), and migrating the F-Droid
build pipeline. We do not plan to rotate before 2050.

If the key is compromised before then, the recovery is:
1. Generate a new key with a new alias.
2. Publish a new release that includes both keys' signatures
   (Android supports APK signature v3 with key rotation via
   `signingConfig` rotation in the Gradle build).
3. Push a Play App Signing key rotation request.
4. F-Droid requires a new package id; the F-Droid build script
   publishes the new id and the old one is archived.
