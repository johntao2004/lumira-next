"use client";

import { useLayoutEffect, useRef } from "react";
import { BoldOutlined, CodeOutlined, FontSizeOutlined, ItalicOutlined, LinkOutlined, OrderedListOutlined, UnorderedListOutlined } from "@ant-design/icons";
import { Button, Empty, Input, Space, Tooltip, Typography } from "antd";
import type { TextAreaRef } from "antd/es/input/TextArea";
import { usePreferences } from "../providers";
import type { MessageKey } from "../i18n/messages";
import { MarkdownContent } from "./markdown-content";
import { AnchorTabs } from "./anchor-tabs";
import { EditorSurface } from "./editor-surface";

type MarkdownEditorProps = {
  anchor: string;
  anchorEnabled?: boolean;
  id?: string;
  value?: string;
  onChange?: (value: string) => void;
  disabled?: boolean;
  maxLength?: number;
};

/** Controlled Markdown source field; preview uses the public agreement renderer. */
export function MarkdownEditor({ anchor, anchorEnabled = true, id, value = "", onChange, disabled, maxLength = 100000 }: MarkdownEditorProps) {
  const { t } = usePreferences();
  const input = useRef<TextAreaRef>(null);
  const selection = useRef<[number, number] | null>(null);

  useLayoutEffect(() => {
    if (!selection.current) return;
    const textarea = input.current?.resizableTextArea?.textArea;
    textarea?.focus();
    textarea?.setSelectionRange(...selection.current);
    selection.current = null;
  }, [value]);

  function insert(before: string, after = "", linePrefix = false) {
    const textarea = input.current?.resizableTextArea?.textArea;
    if (!textarea || textarea.disabled || disabled || !onChange) return;
    const start = linePrefix && textarea.selectionStart > 0 ? value.lastIndexOf("\n", textarea.selectionStart - 1) + 1 : textarea.selectionStart;
    const end = textarea.selectionEnd;
    const selected = value.slice(start, end);
    const replacement = linePrefix ? selected.split("\n").map(line => `${before}${line}`).join("\n") : `${before}${selected}${after}`;
    const nextValue = value.slice(0, start) + replacement + value.slice(end);
    if (nextValue.length > maxLength) return;
    selection.current = linePrefix ? [start, start + replacement.length] : [start + before.length, start + before.length + selected.length];
    onChange(nextValue);
  }

  const actions: { key: MessageKey; icon: React.ReactNode; run: () => void }[] = [
    { key: "markdownHeading", icon: <FontSizeOutlined />, run: () => insert("## ", "", true) },
    { key: "markdownBold", icon: <BoldOutlined />, run: () => insert("**", "**") },
    { key: "markdownItalic", icon: <ItalicOutlined />, run: () => insert("*", "*") },
    { key: "markdownQuote", icon: <span aria-hidden>❯</span>, run: () => insert("> ", "", true) },
    { key: "markdownBulletList", icon: <UnorderedListOutlined />, run: () => insert("- ", "", true) },
    { key: "markdownOrderedList", icon: <OrderedListOutlined />, run: () => insert("1. ", "", true) },
    { key: "markdownLink", icon: <LinkOutlined />, run: () => insert("[", "](https://)") },
    { key: "markdownCode", icon: <CodeOutlined />, run: () => insert("`", "`") },
  ];
  const footer = <>
    <Typography.Text type="secondary">{t("markdownHint")}</Typography.Text>
    <Typography.Text type="secondary">{value.length} / {maxLength}</Typography.Text>
  </>;

  return <div className="markdown-editor">
    <AnchorTabs anchor={anchor} enabled={anchorEnabled} defaultActiveKey="edit" items={[
      { key: "edit", label: t("edit"), children: <EditorSurface
        toolbar={<Space size={4} wrap role="toolbar" aria-label="Markdown">
          {actions.map(action => <Tooltip key={action.key} title={t(action.key)}>
            <Button htmlType="button" size="small" aria-label={t(action.key)} icon={action.icon} disabled={disabled} onMouseDown={event => event.preventDefault()} onClick={action.run} />
          </Tooltip>)}
        </Space>}
        footer={footer}
      >
        <Input.TextArea className="editor-surface-editable markdown-editor-source" id={id} ref={input} rows={7} value={value} onChange={event => onChange?.(event.target.value)} disabled={disabled} maxLength={maxLength} placeholder={t("markdownHint")} />
      </EditorSurface> },
      { key: "preview", label: t("markdownPreview"), children: <EditorSurface footer={footer}>
        {value.trim() ? <MarkdownContent>{value}</MarkdownContent> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("markdownPreviewEmpty")} />}
      </EditorSurface> },
    ]} />
  </div>;
}
