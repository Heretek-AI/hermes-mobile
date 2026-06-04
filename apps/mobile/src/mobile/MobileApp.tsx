// MobileApp: mounts the vendored desktop App inside a MobileShell that
// adds bottom-nav, back-button handling, and Capacitor lifecycle hooks.
//
// In Phase 1, the shell is a passthrough — the desktop's App.tsx does
// all the routing (splash → welcome → install → setup → main). The
// bottom-nav will be activated when the renderer lands on the "main"
// screen in Phase 5.

import { useEffect, useState } from "react";
import { App as CapApp } from "@capacitor/app";
import { StatusBar, Style } from "@capacitor/status-bar";
import { SplashScreen } from "@capacitor/splash-screen";
import { Keyboard } from "@capacitor/keyboard";
import { DesktopApp } from "@hermes/renderer";
import { MobileShell } from "./MobileShell";

export function MobileApp() {
  const [hideShellChrome, setHideShellChrome] = useState(false);

  useEffect(() => {
    // Capacitor lifecycle: hide the splash once the first paint is up,
    // set the status-bar style, and listen for back-button / keyboard.
    (async () => {
      try {
        await SplashScreen.hide();
        await StatusBar.setStyle({ style: Style.Dark });
      } catch {
        // We're in a plain browser (vite dev) — Capacitor plugins
        // throw; that's fine, the splash is just not there.
      }
    })();

    let backSub: Awaited<ReturnType<typeof CapApp.addListener>> | null = null;
    let keyboardSub: Awaited<ReturnType<typeof Keyboard.addListener>> | null = null;
    let keyboardHide: Awaited<ReturnType<typeof Keyboard.addListener>> | null = null;

    void CapApp.addListener("backButton", ({ canGoBack }) => {
      if (!canGoBack) {
        void CapApp.exitApp();
      } else {
        window.history.back();
      }
    }).then((h) => { backSub = h; });

    void Keyboard.addListener("keyboardWillShow", () => {
      document.body.classList.add("keyboard-open");
    }).then((h) => { keyboardSub = h; });

    void Keyboard.addListener("keyboardWillHide", () => {
      document.body.classList.remove("keyboard-open");
    }).then((h) => { keyboardHide = h; });

    return () => {
      void backSub?.remove?.();
      void keyboardSub?.remove?.();
      void keyboardHide?.remove?.();
    };
  }, []);

  return (
    <MobileShell
      hideChrome={hideShellChrome}
      onTabChange={(tab) => {
        // Route the renderer to a top-level screen. For Phase 1, we
        // only navigate the chat tab; sessions/skills/memory/settings
        // land in Phase 5.
        if (tab === "chat") {
          window.history.pushState({}, "", "/chat");
          window.dispatchEvent(new PopStateEvent("popstate"));
        }
      }}
    >
      <DesktopApp />
    </MobileShell>
  );
}
