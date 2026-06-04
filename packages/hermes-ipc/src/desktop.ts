// Desktop bridge: re-exports the Electron `window.hermesAPI` as a typed
// surface. On the desktop, the preload script populates this global; we
// only need to re-export the type and a runtime passthrough.

import type { HermesAPI } from "./types";

export type { HermesAPI };

/** Returns the global `window.hermesAPI` populated by the Electron preload. */
export function getDesktopAPI(): HermesAPI {
  const api = (globalThis as any).hermesAPI;
  if (!api) {
    throw new Error(
      "window.hermesAPI is not defined. Are you running outside the Electron preload?",
    );
  }
  return api as HermesAPI;
}
