// Vendored from hermes-desktop/src/shared/i18n/types.ts.
// Locale identifiers used by the desktop's i18next config and the IPC
// getLocale/setLocale surface.

export type AppLocale =
  | "en"
  | "es"
  | "id"
  | "ja"
  | "pl"
  | "pt-BR"
  | "pt-PT"
  | "tr"
  | "zh-CN"
  | "zh-TW";

export type TranslationTree = {
  [key: string]: string | TranslationTree;
};
