// MobileShell: bottom-nav + safe-area + haptics. Renders children
// (the vendored desktop App) above the nav. The Phase 1 build keeps
// the nav visible only on the "main" screen of the renderer; for the
// install wizard, we hide it via the `hideChrome` prop.

import type { ReactNode } from "react";
import { Haptics, ImpactStyle } from "@capacitor/haptics";

export type MobileTab = "chat" | "sessions" | "skills" | "memory" | "settings";

interface MobileShellProps {
  children: ReactNode;
  hideChrome?: boolean;
  onTabChange?: (tab: MobileTab) => void;
}

export function MobileShell({ children, hideChrome, onTabChange }: MobileShellProps) {
  const tap = (tab: MobileTab) => async () => {
    try {
      await Haptics.impact({ style: ImpactStyle.Light });
    } catch {
      // browser dev mode — no haptics
    }
    // Phase 5: dispatch a CustomEvent that the vendored Layout
    // component listens for (see the patch in
    // apps/mobile/scripts/renderer-patches/screens/Layout/Layout.tsx).
    // The Layout validates the view name against its NAV_ITEMS
    // whitelist and calls its internal goTo(view) which switches
    // the visible pane. We also notify the local onTabChange
    // callback for any non-renderer side effects (analytics etc).
    if (typeof window !== "undefined") {
      window.dispatchEvent(
        new CustomEvent("hermes:mobile-go-to-view", { detail: tab }),
      );
    }
    onTabChange?.(tab);
  };

  return (
    <div className="hermes-mobile-shell">
      <div className="hermes-mobile-shell__main">{children}</div>
      {!hideChrome && (
        <nav className="hermes-mobile-shell__nav" aria-label="Primary">
          <button className="hermes-mobile-shell__tab" onClick={tap("chat")} type="button">
            <ChatIcon />
            <span>Chat</span>
          </button>
          <button className="hermes-mobile-shell__tab" onClick={tap("sessions")} type="button">
            <ListIcon />
            <span>Sessions</span>
          </button>
          <button className="hermes-mobile-shell__tab" onClick={tap("skills")} type="button">
            <SparklesIcon />
            <span>Skills</span>
          </button>
          <button className="hermes-mobile-shell__tab" onClick={tap("memory")} type="button">
            <BrainIcon />
            <span>Memory</span>
          </button>
          <button className="hermes-mobile-shell__tab" onClick={tap("settings")} type="button">
            <CogIcon />
            <span>Settings</span>
          </button>
        </nav>
      )}
    </div>
  );
}

// Inline SVG icons — keeps the mobile bundle small and avoids the
// lucide-react dependency (the renderer still uses it for chat
// attachments etc., but the bottom nav doesn't need it).
const ChatIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
  </svg>
);
const ListIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <line x1="8" y1="6" x2="21" y2="6" />
    <line x1="8" y1="12" x2="21" y2="12" />
    <line x1="8" y1="18" x2="21" y2="18" />
    <line x1="3" y1="6" x2="3.01" y2="6" />
    <line x1="3" y1="12" x2="3.01" y2="12" />
    <line x1="3" y1="18" x2="3.01" y2="18" />
  </svg>
);
const SparklesIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1" />
  </svg>
);
const BrainIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M9 3a3 3 0 0 0-3 3v.5A3 3 0 0 0 3 9.5 3 3 0 0 0 4.5 12 3 3 0 0 0 3 14.5 3 3 0 0 0 6 17.5V18a3 3 0 0 0 6 0V3" />
    <path d="M15 3a3 3 0 0 1 3 3v.5a3 3 0 0 1 3 3 3 3 0 0 1-1.5 2.5 3 3 0 0 1 1.5 2.5 3 3 0 0 1-3 3v.5a3 3 0 0 1-6 0V3" />
  </svg>
);
const CogIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
  </svg>
);
