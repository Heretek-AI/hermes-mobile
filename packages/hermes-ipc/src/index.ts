// Public surface of @hermes/ipc.
export type * from "./types";
export * from "./shared/i18n";
export * from "./shared/attachments";
export * from "./shared/registry";
export * from "./shared/url-key-map";
export { hermesAPI, installMobileBridge } from "./mobile";
export { getDesktopAPI } from "./desktop";
export { makeWebFallback, webFallback } from "./web-fallback";
