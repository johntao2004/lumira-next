"use client";

import { Tooltip } from "antd";

/** A width-constrained cell value that keeps its complete text available on hover and keyboard focus. */
export function EllipsisText({ text }: { text: string }) {
  return (
    <Tooltip
      title={text}
      placement="topLeft"
      styles={{ container: { maxWidth: 480, whiteSpace: "pre-wrap", overflowWrap: "anywhere" } }}
    >
      <span className="resource-table-ellipsis" tabIndex={0} aria-label={text}>
        {text}
      </span>
    </Tooltip>
  );
}
