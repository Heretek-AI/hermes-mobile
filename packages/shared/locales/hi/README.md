# Hindi (hi) locale — Phase 6 i18n addition

The desktop ships 10 locales (en, es, id, ja, pl, pt-BR, pt-PT, tr, zh-CN, zh-TW). Phase 6 adds Hindi (hi) for the mobile launch.

The locale catalog is auto-loaded by the vendored renderer's
I18nProvider from `packages/shared/i18n/index.ts`. Adding a new
locale means:

1. Create `packages/shared/locales/hi/{common,navigation,chat,...}.json`
   with the same key structure as the existing locales.
2. Add the `hi` entry to `APP_LOCALES` in `packages/shared/i18n/config.ts`.

This file (`common.json`) covers the most-used strings. The
remaining namespace files (navigation, chat, etc.) follow the
same pattern; contribute translations via the F-Droid
translation workflow at https://weblate.fdroid.org or via PR
to this repo.

The renderer is read-only in the vendored tree, so the locale
catalog addition goes here in `packages/shared/locales/hi/`
(which the vendored renderer imports via the relative `../../../../shared/locales/hi/`
path) and in the I18nProvider's config file (also vendored).

# Phase 6 mobile launch priorities

Top locales by user count (Nous Research active users, 2026 H1):

| Locale | Speakers (M) | Status |
|---|---|---|
| en | 1500 | baseline |
| zh-CN | 1100 | desktop v0.5 |
| es | 550 | desktop v0.5 |
| hi | 600 | mobile v0.6 (Phase 6) |
| pt-BR | 260 | desktop v0.5 |
| ar | 370 | Phase 7 candidate |
| ja | 125 | desktop v0.5 |

The mobile launch prioritizes hi (Hindi) over ja/ko because the
Android marketshare in India is large and Hermes-agent's English-
only UI has been the primary barrier to adoption. Japanese
and Korean follow in Phase 7.
