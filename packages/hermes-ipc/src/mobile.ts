// Mobile bridge: routes every HermesAPI call through the Capacitor
// HermesAPIPlugin running in the Android WebView host. The plugin is
// declared in android-runner/.../HermesAPIPlugin.kt.
//
// Method-style calls (returning a Promise) go through `Plugin.call`. Event-
// style calls (returning an unsubscribe function) are wrapped with
// `addListener`/`remove`. The Proxy below makes both styles look identical
// to the renderer, so the vendored desktop code is used unchanged.

import { registerPlugin, type Plugin } from "@capacitor/core";
import type { HermesAPI } from "./types";

// Re-export the type so consumers can `import type { HermesAPI } from "@hermes/ipc/mobile"`.
export type { HermesAPI };

// Names that should be treated as events (callback-style) — they return an
// unsubscribe function instead of a Promise. We detect these by name rather
// than by `typeof === 'function'` because the renderer calls them as
// `hermesAPI.onChatChunk(cb)` with a callback arg, then expects a function
// back (the unsubscribe).
const EVENT_METHODS = new Set<string>([
  "onInstallProgress",
  "onOAuthLoginProgress",
  "onContextMenuCopyChat",
  "onContextMenuSelectBubble",
  "onChatChunk",
  "onChatReasoningChunk",
  "onChatDone",
  "onChatToolProgress",
  "onChatUsage",
  "onChatError",
  "onClaw3dSetupProgress",
  "onUpdateAvailable",
  "onUpdateDownloadProgress",
  "onUpdateDownloaded",
  "onUpdateError",
  "onMenuNewChat",
  "onMenuSearchSessions",
  "onDeepLink",
  "onSharedText",
  "onGatewayStateChange",
]);

// The native plugin doesn't implement the full 188-method surface in v1 —
// we expose everything as a Proxy and let unimplemented methods throw at
// call time. This way the type contract is complete (renderer code
// typechecks) and the native side can fill in methods incrementally.
interface NativePlugin extends Partial<Plugin> {
  [key: string]: unknown;
}

const HermesAPINative = registerPlugin<NativePlugin>("HermesAPI");

// Bridge wrapper. Each event method becomes `(cb) => handle.remove()`.
// Each method method becomes `(...args) => Plugin.call(args)`.
function makeBridge(): HermesAPI {
  return new Proxy({} as HermesAPI, {
    get(_target, prop) {
      if (typeof prop !== "string") return undefined;
      if (prop === "isAndroid") {
        return async () => {
          const cap = (HermesAPINative as any).getPlatform;
          if (typeof cap === "function") {
            const res = await cap();
            return res?.platform === "android";
          }
          return false;
        };
      }
      if (EVENT_METHODS.has(prop)) {
        return (cb: (...args: any[]) => void) => {
          const handle = (HermesAPINative as any).addListener(prop, (e: any) => cb(e));
          return () => {
            if (handle && typeof handle.remove === "function") handle.remove();
          };
        };
      }
      // Method-style: pass-through to the native plugin.
      return (...args: any[]) => {
        const fn = (HermesAPINative as any)[prop];
        if (typeof fn !== "function") {
          return Promise.reject(
            new Error(`HermesAPI.${prop} is not implemented on mobile yet`),
          );
        }
        return fn(...args);
      };
    },
  });
}

export const hermesAPI: HermesAPI = makeBridge();

/**
 * Inject the mobile bridge as `window.hermesAPI` so the vendored desktop
 * renderer can call it without any patches to App.tsx. Call this once at
 * the top of the mobile entry, before React renders.
 */
export function installMobileBridge(): void {
  if (typeof window === "undefined") return;
  (window as any).hermesAPI = hermesAPI;
}

/**
 * Set `window.electron` to a minimal "android" stub. The vendored
 * renderer reads `window.electron.process.platform` (e.g. in
 * App.tsx's isMac check) and `window.electron.process.versions` (in
 * Versions.tsx). The desktop's preload sets this from real Node/Electron;
 * the mobile just needs the shape to be present so the typecheck
 * passes and the runtime reads don't throw.
 */
export function installMobilePlatformShim(): void {
  if (typeof window === "undefined") return;
  (window as any).electron = {
    process: {
      platform: "android" as any,
      versions: {
        chrome: "WebView",
        electron: "0.0.0-mobile",
        node: "0.0.0-webview",
      },
    },
  };
}
