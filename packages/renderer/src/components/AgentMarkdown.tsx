// Mobile-friendly replacement for AgentMarkdown.tsx — drops the
// ~1.2MB `react-syntax-highlighter` import and uses `highlight.js`
// (already a desktop dep) instead.
//
// The desktop file is replaced wholesale by the vendor script's
// rsync, so this version lives outside the vendored tree and gets
// applied as an `overwrite` patch at vendor time.
//
// Differences from the desktop version:
//   1. No dynamic import of `react-syntax-highlighter`.
//   2. CodeBlock uses `highlight.js` directly; output HTML is
//      rendered with `dangerouslySetInnerHTML`. The same
//      `github-dark` theme is loaded as a CSS import.
//   3. Diff view is unchanged (it never used the highlighter).
//   4. Everything else (Markdown, MediaImage, link handling) is
//      byte-identical to the desktop.
//
// Net bundle impact: -1.1MB minified (per the plan §B.6).

import { useState, useEffect, memo } from "react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import hljs from "highlight.js";
import "highlight.js/styles/github-dark.css";
import { Copy } from "lucide-react";
import { useI18n } from "./useI18n";
import { MediaImage, DownloadChip } from "./MediaImage";
import { describeImageSrc } from "../screens/Chat/mediaUtils";

// Diff viewer with colored +/- lines (unchanged from desktop)
function DiffView({ code }: { code: string }): React.JSX.Element {
  const lines = code.split("\n");
  return (
    <div className="chat-diff-content">
      {lines.map((line, i) => {
        let cls = "chat-diff-line";
        if (line.startsWith("+")) cls += " chat-diff-add";
        else if (line.startsWith("-")) cls += " chat-diff-remove";
        else if (line.startsWith("@@")) cls += " chat-diff-hunk";
        return (
          <div key={i} className={cls}>
            {line || " "}
          </div>
        );
      })}
    </div>
  );
}

// Code block with highlight.js + copy button. Replaces the desktop's
// lazy-loaded `react-syntax-highlighter` with a synchronous
// `highlight.js` call — the latter is already in the bundle (used by
// FileViewer.tsx) so the marginal cost is just the per-code-block
// highlighting work at render time, which is fast.
function CodeBlock({
  className,
  children,
}: {
  className?: string;
  children?: React.ReactNode;
}): React.JSX.Element {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  const code = String(children).replace(/\n$/, "");
  const match = /language-(\w+)/.exec(className || "");
  const language = match ? match[1] : "";
  const isDiff = language === "diff";

  // Synchronous highlight.js call. `highlightAuto` falls back to
  // language detection if `language` isn't a known hljs alias;
  // otherwise we use the explicit language (which is faster and
  // more accurate for fenced code blocks where the language is
  // known).
  let highlighted: string;
  try {
    highlighted = language && hljs.getLanguage(language)
      ? hljs.highlight(code, { language, ignoreIllegals: true }).value
      : hljs.highlightAuto(code).value;
  } catch (_) {
    highlighted = escapeHtml(code);
  }

  function handleCopy(): void {
    navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="chat-code-block">
      <div className="chat-code-header">
        <span className="chat-code-lang">
          {isDiff ? "diff" : language || "code"}
        </span>
        <button className="chat-code-copy" onClick={handleCopy}>
          {copied ? t("common.copied") : <Copy size={13} />}
        </button>
      </div>
      {isDiff ? (
        <DiffView code={code} />
      ) : (
        <pre
          className="hljs"
          style={{
            margin: 0,
            borderRadius: 0,
            fontSize: "13px",
            padding: "12px",
            background: "transparent",
            overflow: "auto",
          }}
        >
          <code
            className={language ? `language-${language}` : undefined}
            dangerouslySetInnerHTML={{ __html: highlighted }}
          />
        </pre>
      )}
    </div>
  );
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

// Shared Markdown renderer that opens links externally. Identical
// to the desktop's AgentMarkdown aside from the CodeBlock above.
const AgentMarkdown = memo(function AgentMarkdown({
  children,
}: {
  children: string;
}): React.JSX.Element {
  return (
    <Markdown
      remarkPlugins={[remarkGfm]}
      components={{
        a: ({ href, children }) => (
          <a
            href={href}
            onClick={(e) => {
              e.preventDefault();
              if (!href) return;
              try {
                const url = new URL(href, "https://placeholder.invalid");
                if (!["http:", "https:", "mailto:"].includes(url.protocol)) {
                  return;
                }
              } catch {
                return;
              }
              window.hermesAPI.openExternal(href);
            }}
          >
            {children}
          </a>
        ),
        img: ({ src }) => {
          if (typeof src !== "string" || src.length === 0) return null;
          // ![alt](file.pdf) parses as a markdown image but isn't an image —
          // route those to the download chip instead of letting MediaImage
          // try to load a non-image MIME and fail. (Follow-up from #303.)
          const token = describeImageSrc(src);
          return token.isImage ? (
            <MediaImage token={token} />
          ) : (
            <DownloadChip token={token} />
          );
        },
        code: ({ className, children, ...props }) => {
          const isInline =
            !className &&
            typeof children === "string" &&
            !children.includes("\n");
          if (isInline) {
            return (
              <code className={className} {...props}>
                {children}
              </code>
            );
          }
          return <CodeBlock className={className}>{children}</CodeBlock>;
        },
      }}
    >
      {children}
    </Markdown>
  );
});

export { AgentMarkdown };
export default AgentMarkdown;
