"use client";

import Markdown from "react-markdown";

/** Render CommonMark as React nodes, skipping embedded HTML and remote images. */
export function FilePreviewMarkdown({ children }: { children: string }) {
  return <div className="file-preview-markdown" style={{ overflowWrap: "anywhere" }}>
    <Markdown
      skipHtml
      components={{
        img: ({ alt }) => <span>{alt}</span>,
        a: ({ children: linkText, href }) => <a href={href} target="_blank" rel="noopener noreferrer">{linkText}</a>,
        pre: ({ children: code }) => <pre style={{ overflowX: "auto", whiteSpace: "pre" }}>{code}</pre>,
      }}
    >{children}</Markdown>
  </div>;
}
