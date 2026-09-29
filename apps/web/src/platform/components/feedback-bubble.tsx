"use client";

import { App } from "antd";
import { useEffect, useId, useRef } from "react";

type FeedbackKind = "success" | "error" | "warning" | "info";

/**
 * Emits one contextual Ant Design message when an operation changes state.
 *
 * Operation feedback should not take up layout space or push a form around.
 * Use field help for entry guidance, Alerts for important current conditions,
 * and this component for transient save, delete, submit, and auth feedback.
 */
export function FeedbackBubble({
  kind = "error",
  content,
  id,
  duration = 3,
}: {
  kind?: FeedbackKind;
  content?: React.ReactNode;
  id?: string;
  duration?: number;
}) {
  const { message } = App.useApp();
  const generatedId = useId();
  const key = id ?? `feedback-${generatedId}`;
  const last = useRef<string | null>(null);

  useEffect(() => {
    if (content === undefined || content === null || content === "") {
      last.current = null;
      message.destroy(key);
      return;
    }
    const token = `${kind}:${String(content)}`;
    if (last.current === token) return;
    last.current = token;
    const args = { content, key, duration };
    if (kind === "success") void message.success(args);
    else if (kind === "warning") void message.warning(args);
    else if (kind === "info") void message.info(args);
    else void message.error(args);
  }, [content, duration, key, kind, message]);

  return null;
}
