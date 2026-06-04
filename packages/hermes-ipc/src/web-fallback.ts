// Web fallback: a no-op HermesAPI implementation used by unit tests
// (Vitest, Playwright) and by the desktop's renderer when run in a plain
// browser (the storybook / preview server). Every method returns a
// sensible empty value; tests can override individual methods on the
// returned object.

import type { HermesAPI } from "./types";

export type { HermesAPI };

function notImpl(name: string): never {
  throw new Error(`web-fallback: HermesAPI.${name} called in a non-Electron context`);
}

const noopUnsub = () => {};

export function makeWebFallback(): HermesAPI {
  const api = {
    isAndroid: async () => false,
    isRemoteMode: async () => false,
    isRemoteOnlyMode: async () => false,
    getAppVersion: async () => "0.0.0-web",
    getHermesVersion: async () => null,
    checkForUpdates: async () => null,
    getConnectionConfig: async () => ({
      mode: "local" as const,
      remoteUrl: "",
      hasApiKey: false,
      apiKeyLength: 0,
      ssh: { host: "", port: 22, username: "", keyPath: "", remotePort: 8642, localPort: 8642 },
    }),
    setConnectionConfig: async () => false,
    testRemoteConnection: async () => false,
    getEnv: async () => ({}),
    setEnv: async () => false,
    getConfig: async () => null,
    setConfig: async () => false,
    listProfiles: async () => [],
    getPlatformEnabled: async () => ({}),
    getToolsets: async () => [],
    listInstalledSkills: async () => [],
    listBundledSkills: async () => [],
    listModels: async () => [],
    listCronJobs: async () => [],
    listSessions: async () => [],
    listCachedSessions: async () => [],
    searchSessions: async () => [],
    syncSessionCache: async () => [],
    getApiServerKeyStatus: async () => ({ hasKey: false }),
    gatewayStatus: async () => false,
    quitApp: async () => {},
    onInstallProgress: () => noopUnsub,
    onChatChunk: () => noopUnsub,
    onChatReasoningChunk: () => noopUnsub,
    onChatDone: () => noopUnsub,
    onChatToolProgress: () => noopUnsub,
    onChatUsage: () => noopUnsub,
    onChatError: () => noopUnsub,
    onUpdateAvailable: () => noopUnsub,
    onUpdateDownloadProgress: () => noopUnsub,
    onUpdateDownloaded: () => noopUnsub,
    onUpdateError: () => noopUnsub,
    onMenuNewChat: () => noopUnsub,
    onMenuSearchSessions: () => noopUnsub,
    onOAuthLoginProgress: () => noopUnsub,
    onContextMenuCopyChat: () => noopUnsub,
    onContextMenuSelectBubble: () => noopUnsub,
    onClaw3dSetupProgress: () => noopUnsub,
    onDeepLink: () => noopUnsub,
    onSharedText: () => noopUnsub,
    onGatewayStateChange: () => noopUnsub,
  } as unknown as HermesAPI;
  return api;
}

export const webFallback: HermesAPI = makeWebFallback();
