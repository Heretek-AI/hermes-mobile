// Mobile-friendly shell + responsive helpers. Phase 5 patches.
//
// - useResponsive(): returns 'mobile' (<640dp) or 'desktop'. All
//   screen-level mobile vs desktop JSX branches use this hook
//   instead of fighting with CSS @media queries inside the
//   component tree.
// - MobileDrawer: a CSS transform slide-up shim for the desktop's
//   headlessui Dialog at <640dp. Modal content stays the same; the
//   chrome swaps to a bottom sheet.
// - installMobileNavListener(): MobileShell calls this to wire
//   the bottom-nav tabs to the renderer's Layout view state via
//   a CustomEvent ('hermes:mobile-go-to-view'). The Layout
//   component listens for that event and calls its internal
//   goTo(view) — see apps/mobile/scripts/renderer-patches/screens/Layout.tsx.

import { useEffect, useState } from "react";

export type Viewport = "mobile" | "desktop";

const MOBILE_BREAKPOINT = 640; // Tailwind's sm boundary

export function useResponsive(): Viewport {
  const [vp, setVp] = useState<Viewport>(() =>
    typeof window === "undefined"
      ? "desktop"
      : window.innerWidth < MOBILE_BREAKPOINT
        ? "mobile"
        : "desktop",
  );
  useEffect(() => {
    if (typeof window === "undefined") return;
    let raf: number | null = null;
    const onResize = () => {
      if (raf != null) return;
      raf = window.requestAnimationFrame(() => {
        raf = null;
        setVp(window.innerWidth < MOBILE_BREAKPOINT ? "mobile" : "desktop");
      });
    };
    window.addEventListener("resize", onResize);
    return () => {
      window.removeEventListener("resize", onResize);
      if (raf != null) window.cancelAnimationFrame(raf);
    };
  }, []);
  return vp;
}

/**
 * Bottom-sheet drawer used at <640dp instead of the headlessui
 * Dialog. Same children; different chrome. The desktop's modal
 * portal logic continues to work; we just override the visible
 * position via CSS.
 */
export function MobileDrawer({
  open,
  onClose,
  children,
  title,
}: {
  open: boolean;
  onClose: () => void;
  children: React.ReactNode;
  title?: string;
}): React.JSX.Element {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);
  if (!open) return <></>;
  return (
    <>
      <div
        className="mobile-drawer-backdrop"
        onClick={onClose}
        role="presentation"
        style={{
          position: "fixed",
          inset: 0,
          background: "rgba(0,0,0,0.5)",
          zIndex: 1000,
        }}
      />
      <div
        className="mobile-drawer"
        role="dialog"
        aria-label={title}
        style={{
          position: "fixed",
          left: 0,
          right: 0,
          bottom: 0,
          maxHeight: "85dvh",
          background: "#1a1a1a",
          color: "#fff",
          borderTopLeftRadius: 16,
          borderTopRightRadius: 16,
          zIndex: 1001,
          transform: "translateY(0)",
          transition: "transform 200ms ease",
          padding: "16px 16px calc(16px + env(safe-area-inset-bottom, 0px))",
          overflowY: "auto",
        }}
      >
        {title && (
          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              marginBottom: 12,
              paddingBottom: 8,
              borderBottom: "1px solid rgba(255,255,255,0.1)",
            }}
          >
            <h2 style={{ fontSize: 16, fontWeight: 600, margin: 0 }}>{title}</h2>
            <button
              onClick={onClose}
              aria-label="Close"
              style={{
                background: "transparent",
                border: 0,
                color: "#fff",
                fontSize: 24,
                cursor: "pointer",
                padding: 4,
              }}
            >
              ×
            </button>
          </div>
        )}
        {children}
      </div>
    </>
  );
}

/**
 * Wire the bottom-nav tab tap to the Layout's internal view state.
 * MobileShell dispatches 'hermes:mobile-go-to-view' on tap; Layout
 * listens via the patch in apps/mobile/scripts/renderer-patches/screens/Layout.tsx.
 */
export const MOBILE_GO_TO_VIEW_EVENT = "hermes:mobile-go-to-view";

export function installMobileNavListener(
  onNavigate?: (view: string) => void,
): () => void {
  if (typeof window === "undefined") return () => {};
  const handler = (e: Event) => {
    const view = (e as CustomEvent<string>).detail;
    onNavigate?.(view);
  };
  window.addEventListener(MOBILE_GO_TO_VIEW_EVENT, handler);
  return () => window.removeEventListener(MOBILE_GO_TO_VIEW_EVENT, handler);
}

export function dispatchMobileNav(view: string): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(
    new CustomEvent(MOBILE_GO_TO_VIEW_EVENT, { detail: view }),
  );
}
