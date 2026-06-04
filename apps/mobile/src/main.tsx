// Mobile entry: installs the IPC bridge, then mounts the vendored
// desktop renderer inside the MobileShell. The desktop's main.tsx is
// not used directly — we replicate its bare minimum here (analytics
// init, I18nProvider, ErrorBoundary) so the desktop's App.tsx works
// unmodified.

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { I18nProvider } from "@hermes/renderer";
import { MobileApp } from "./mobile/MobileApp";
import "./mobile/styles.css";

// Install the mobile HermesAPI bridge on `window.hermesAPI` BEFORE any
// component mounts, so the vendored desktop code that calls
// `window.hermesAPI.getConnectionConfig()` at first render works.
import { installMobileBridge } from "@hermes/ipc/mobile";
installMobileBridge();

const root = createRoot(document.getElementById("root")!);
root.render(
  <StrictMode>
    <I18nProvider>
      <MobileApp />
    </I18nProvider>
  </StrictMode>,
);
