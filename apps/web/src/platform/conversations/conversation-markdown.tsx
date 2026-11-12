import { Children, useMemo } from "react";
import { CodeHighlighter } from "@ant-design/x";
import { XMarkdown, type ComponentProps } from "@ant-design/x-markdown";
import { oneDark, oneLight } from "react-syntax-highlighter/dist/esm/styles/prism";
import "@ant-design/x-markdown/themes/light.css";
import "@ant-design/x-markdown/themes/dark.css";

export function AiChatMarkdown({ content, dark }: { content: string; dark: boolean }) {
  const components = useMemo(() => ({
    pre: ({ children }: ComponentProps) => <div className="ai-chat-code-block">{children}</div>,
    code: ({ children, block, lang, className }: ComponentProps) => block
      ? <CodeHighlighter
        lang={lang?.trim().split(/\s+/)[0] || "text"}
        prismLightMode={false}
        highlightProps={{ style: dark ? oneDark : oneLight, customStyle: { margin: 0 }, wrapLongLines: false }}
      >{Children.toArray(children).join("")}</CodeHighlighter>
      : <code className={className}>{children}</code>,
    input: ({ checked }: ComponentProps) => <input type="checkbox" checked={Boolean(checked)} disabled readOnly />,
  }), [dark]);

  return <XMarkdown
    className={`ai-chat-markdown x-markdown-${dark ? "dark" : "light"}`}
    content={content}
    components={components}
    config={{ gfm: true }}
    disableDefaultStyles={["code"]}
    escapeRawHtml
    openLinksInNewTab
    dompurifyConfig={{ FORBID_TAGS: ["img", "iframe", "form", "button", "video", "audio", "style"], FORBID_ATTR: ["style"] }}
  />;
}
