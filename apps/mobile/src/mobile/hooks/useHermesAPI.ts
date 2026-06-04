// Convenience hook for the renderer (and any custom mobile UI) to access
// the typed HermesAPI surface. Mostly a re-export — the Proxy from
// @hermes/ipc/mobile already gives you a fully-typed object via the
// `hermesAPI` named export. This hook exists so call sites can do:
//
//   const api = useHermesAPI();
//   await api.getConnectionConfig();
//
// instead of importing the bridge directly. (Useful for code-split
// bundles and for testing.)

import { hermesAPI } from "@hermes/ipc/mobile";
import type { HermesAPI } from "@hermes/ipc";

export function useHermesAPI(): HermesAPI {
  return hermesAPI;
}
