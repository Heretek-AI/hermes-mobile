import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "path";

// Vite config for the mobile Capacitor shell.
//
// We mount the vendored desktop renderer (@hermes/renderer) and wrap it
// in a small MobileShell that adds bottom-nav, back-button handling,
// safe-area, and haptics. The renderer is otherwise unmodified — the
// only requirement is that `window.hermesAPI` is populated before the
// renderer's App.tsx mounts (see src/main.tsx).

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: [
      // Subpath imports must come BEFORE the bare-name aliases, otherwise
      // Vite resolves `@hermes/ipc` to `index.ts` and appends `/mobile`.
      { find: /^@hermes\/ipc\/mobile$/, replacement: resolve(__dirname, "../../packages/hermes-ipc/src/mobile.ts") },
      { find: /^@hermes\/ipc\/desktop$/, replacement: resolve(__dirname, "../../packages/hermes-ipc/src/desktop.ts") },
      { find: /^@hermes\/ipc\/web-fallback$/, replacement: resolve(__dirname, "../../packages/hermes-ipc/src/web-fallback.ts") },
      { find: /^@hermes\/ipc\//, replacement: resolve(__dirname, "../../packages/hermes-ipc/src/") },
      { find: "@hermes/ipc", replacement: resolve(__dirname, "../../packages/hermes-ipc/src/index.ts") },
      { find: /^@hermes\/renderer\//, replacement: resolve(__dirname, "../../packages/renderer/src/") },
      { find: "@hermes/renderer", replacement: resolve(__dirname, "../../packages/renderer/src/index.ts") },
      // posthog-js stub: the desktop uses posthog-js for analytics;
      // the mobile shell uses a no-op shim and forwards to HermesAPI.trackEvent.
      { find: /^posthog-js$/, replacement: resolve(__dirname, "../../packages/renderer/src/shims/posthog-js.ts") },
    ],
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    target: "es2020",
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ["react", "react-dom"],
        },
      },
    },
  },
  server: {
    host: "0.0.0.0",
    port: 5173,
  },
});
