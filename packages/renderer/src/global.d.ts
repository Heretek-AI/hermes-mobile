// Global types for the vendored renderer. The desktop exposes
// `window.hermesAPI` from its preload script; the mobile injects the
// @hermes/ipc bridge at startup with the same shape. The
// `window.electron` global is the desktop's main-process info; the
// mobile bridge sets it to a stub `{platform: 'android'}` at startup.

import type { HermesAPI } from "@hermes/ipc";

declare global {
  interface Window {
    hermesAPI: HermesAPI;
    // Declared required (not optional) so vendored code like
    // Versions.tsx, which does `window.electron.process.versions`
    // without optional chaining, typechecks. The mobile bridge sets
    // a stub at runtime; the desktop's preload sets the real values.
    electron: {
      process: { platform: string; versions: { chrome: string; electron: string; node: string } };
    };
  }

  // Vite-injected env (renderer runs in Vite dev / WebView at build time).
  interface ImportMetaEnv {
    readonly VITE_POSTHOG_KEY?: string;
    readonly VITE_POSTHOG_HOST?: string;
    readonly MODE?: string;
    readonly DEV?: boolean;
    readonly PROD?: boolean;
  }
  interface ImportMeta {
    readonly env: ImportMetaEnv;
  }
}

export {};
