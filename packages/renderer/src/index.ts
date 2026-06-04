// Public surface of @hermes/renderer. The mobile shell imports
// `DesktopApp` (the unmodified App.tsx from the desktop) and wraps it in
// a MobileShell. The vendored renderer is otherwise untouched.

export { default as DesktopApp } from "./App";
export { I18nProvider } from "./components/I18nProvider";
