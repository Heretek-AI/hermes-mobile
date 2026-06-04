// Stub for the desktop's posthog-js dependency. The renderer's
// utils/analytics.ts imports posthog from "posthog-js"; we replace
// that with a no-op module that posts events through the HermesAPI
// trackEvent method (which forwards to a debug log on Android, with
// PostHog opt-in deferred to v2).
//
// The renderer's analytics file imports the default export and calls
// posthog.init / posthog.capture / etc. We export a tiny stand-in
// that satisfies the type surface.

const noop = (..._args: unknown[]) => undefined;

const posthog = {
  init: noop,
  capture: noop,
  identify: noop,
  reset: noop,
  opt_in_capturing: noop,
  opt_out_capturing: noop,
  has_opted_in_capturing: () => false,
  has_opted_out_capturing: () => false,
  people: { set: noop },
  register: noop,
  unregister: noop,
  get_feature_flag: () => undefined,
  isFeatureEnabled: () => false,
  onFeatureFlags: noop,
  setPersonProperties: noop,
  debug: () => false,
};

export default posthog;
