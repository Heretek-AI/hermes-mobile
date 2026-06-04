// Vendored from hermes-desktop/src/shared/url-key-map.ts (105 LOC).
// URL → env-var mapping for OpenAI-compatible providers. Used by the
// desktop to decide which env var holds the API key for a given base
// URL. The mobile renderer doesn't currently call this, but we keep it
// available for parity so future screens that import it from "@hermes/ipc"
// work without a new vendoring pass.

export interface UrlKeyMapping {
  pattern: RegExp;
  envKey: string;
}

export const URL_KEY_MAP: ReadonlyArray<UrlKeyMapping> = [
  { pattern: /openrouter\.ai/i, envKey: "OPENROUTER_API_KEY" },
  { pattern: /anthropic\.com/i, envKey: "ANTHROPIC_API_KEY" },
  { pattern: /openai\.com/i, envKey: "OPENAI_API_KEY" },
  { pattern: /huggingface\.co/i, envKey: "HF_TOKEN" },
  { pattern: /api\.groq\.com/i, envKey: "GROQ_API_KEY" },
  { pattern: /api\.deepseek\.com/i, envKey: "DEEPSEEK_API_KEY" },
  { pattern: /api\.together\.xyz/i, envKey: "TOGETHER_API_KEY" },
  { pattern: /api\.fireworks\.ai/i, envKey: "FIREWORKS_API_KEY" },
  { pattern: /api\.cerebras\.ai/i, envKey: "CEREBRAS_API_KEY" },
  { pattern: /api\.mistral\.ai/i, envKey: "MISTRAL_API_KEY" },
  { pattern: /api\.perplexity\.ai/i, envKey: "PERPLEXITY_API_KEY" },
];

export const CUSTOM_API_KEY_ENV = "CUSTOM_API_KEY";

export function expectedEnvKeyForUrl(url: string | null | undefined): string {
  if (!url) return CUSTOM_API_KEY_ENV;
  for (const { pattern, envKey } of URL_KEY_MAP) {
    if (pattern.test(url)) return envKey;
  }
  return CUSTOM_API_KEY_ENV;
}

export function isKnownProviderUrl(url: string | null | undefined): boolean {
  if (!url) return false;
  return URL_KEY_MAP.some(({ pattern }) => pattern.test(url));
}

export function isLocalBaseUrl(url: string | null | undefined): boolean {
  if (!url) return false;
  return /^https?:\/\/(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]|\[::\]|192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)/i.test(
    url,
  );
}

export const OPENAI_COMPAT_PROVIDERS: ReadonlySet<string> = new Set([
  "custom",
  "lmstudio",
  "ollama",
  "vllm",
  "llamacpp",
  "groq",
  "deepseek",
  "together",
  "fireworks",
  "cerebras",
  "mistral",
]);
