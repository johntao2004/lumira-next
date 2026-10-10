"use client";

import {
  BoldOutlined,
  PictureOutlined,
  ItalicOutlined,
  OrderedListOutlined,
  UnorderedListOutlined,
} from "@ant-design/icons";
import { Button, Space, Typography } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { upload } from "@/platform/api/client";
import { usePreferences } from "@/platform/providers";
import { EditorSurface } from "./editor-surface";

const allowedTags = new Set([
  "P",
  "BR",
  "DIV",
  "SPAN",
  "STRONG",
  "B",
  "EM",
  "I",
  "U",
  "S",
  "UL",
  "OL",
  "LI",
  "BLOCKQUOTE",
  "PRE",
  "CODE",
  "H1",
  "H2",
  "H3",
  "H4",
  "H5",
  "H6",
  "A",
  "IMG",
]);

/** Keep the browser editor and preview at the same safe HTML boundary. */
export function sanitizeRichText(source: string): string {
  if (typeof DOMParser === "undefined") return source.replace(/<script[\s\S]*?<\/script>/gi, "");
  const document = new DOMParser().parseFromString(source, "text/html");
  document.body.querySelectorAll("*").forEach((element) => {
    if (!allowedTags.has(element.tagName)) {
      element.replaceWith(...Array.from(element.childNodes));
      return;
    }
    const allowedAttributes = element.tagName === "A"
      ? new Set(["href", "title"])
      : element.tagName === "IMG"
        ? new Set(["src", "alt", "title", "loading", "width", "height"])
        : new Set<string>();
    Array.from(element.attributes).forEach((attribute) => {
      const name = attribute.name.toLowerCase();
      const value = attribute.value.trim();
      if (!allowedAttributes.has(name)) {
        element.removeAttribute(attribute.name);
        return;
      }
      if ((name === "href" || name === "src") && !/^(?:https?:\/\/|\/(?!\/))/i.test(value)) {
        element.removeAttribute(attribute.name);
      }
    });
    if (element.tagName === "IMG" && !element.getAttribute("src")) element.remove();
    if (element.tagName === "A") {
      element.setAttribute("rel", "nofollow noopener noreferrer");
      element.setAttribute("target", "_blank");
    }
  });
  return document.body.innerHTML;
}

export function hasRichTextContent(source: string): boolean {
  if (typeof DOMParser === "undefined") return source.replace(/<[^>]*>/g, "").trim().length > 0;
  const document = new DOMParser().parseFromString(sanitizeRichText(source), "text/html");
  return Boolean(
    document.body.querySelector("img[src]") ||
      (document.body.textContent ?? "").replace(/[\u200b\s]/g, "").length,
  );
}

export function RichTextEditor({
  value,
  onChange,
  disabled = false,
  placeholder,
  imageUploadPath,
  imageButtonLabel = "Insert image",
  imageUploadingLabel = "Uploading image…",
  imageUploadError = "Image upload failed",
}: {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  placeholder?: string;
  /** When present, enable normalized shared-image upload and paste/drop handling. */
  imageUploadPath?: string;
  imageButtonLabel?: string;
  imageUploadingLabel?: string;
  imageUploadError?: string;
}) {
  const { t } = usePreferences();
  const editorRef = useRef<HTMLDivElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);
  const lastValue = useRef("");
  const selection = useRef<Range | null>(null);
  const [busy, setBusy] = useState(false);
  const [imageBusy, setImageBusy] = useState(false);
  const [imageError, setImageError] = useState(false);

  useEffect(() => {
    const editor = editorRef.current;
    const next = sanitizeRichText(value);
    if (editor && next !== lastValue.current && next !== editor.innerHTML) {
      editor.innerHTML = next;
      lastValue.current = next;
    }
  }, [value]);

  const rememberSelection = useCallback(() => {
    const current = window.getSelection();
    if (current?.rangeCount && editorRef.current?.contains(current.anchorNode)) {
      selection.current = current.getRangeAt(0).cloneRange();
    }
  }, []);

  const restoreSelection = useCallback(() => {
    const editor = editorRef.current;
    const current = window.getSelection();
    if (!editor || !current) return;
    editor.focus();
    if (!selection.current) return;
    current.removeAllRanges();
    current.addRange(selection.current);
  }, []);

  const emit = useCallback(() => {
    const html = sanitizeRichText(editorRef.current?.innerHTML ?? "");
    lastValue.current = html;
    onChange(html);
  }, [onChange]);

  const execute = useCallback(
    (command: string) => {
      if (disabled) return;
      setBusy(true);
      restoreSelection();
      document.execCommand(command);
      emit();
      setBusy(false);
    },
    [disabled, emit, restoreSelection],
  );

  const insertImage = useCallback(async (file: File) => {
    if (!imageUploadPath || disabled || imageBusy || !file.type.startsWith("image/")) return;
    rememberSelection();
    setImageError(false);
    setImageBusy(true);
    try {
      const result = await upload<{ id: string; url?: string }>(
        `${imageUploadPath}?filename=${encodeURIComponent(file.name || "image.png")}`,
        file,
      );
      const editor = editorRef.current;
      if (!editor) return;
      restoreSelection();
      const image = document.createElement("img");
      image.src = result.url ?? `${imageUploadPath}/${result.id}`;
      image.alt = file.name || "image";
      image.loading = "lazy";
      const current = window.getSelection();
      const range = current?.rangeCount ? current.getRangeAt(0) : null;
      if (range && editor.contains(range.commonAncestorContainer)) {
        range.deleteContents();
        range.insertNode(image);
        range.setStartAfter(image);
        range.collapse(true);
        current?.removeAllRanges();
        current?.addRange(range);
      } else {
        editor.append(image);
      }
      emit();
    } catch {
      setImageError(true);
    } finally {
      setImageBusy(false);
      if (imageInputRef.current) imageInputRef.current.value = "";
    }
  }, [disabled, emit, imageBusy, imageUploadPath, rememberSelection, restoreSelection]);

  const pickImage = useCallback(() => {
    if (disabled || imageBusy || !imageUploadPath) return;
    rememberSelection();
    imageInputRef.current?.click();
  }, [disabled, imageBusy, imageUploadPath, rememberSelection]);

  const handleImageFiles = useCallback((files: FileList | null) => {
    const file = files ? Array.from(files).find((candidate) => candidate.type.startsWith("image/")) : undefined;
    if (file) void insertImage(file);
  }, [insertImage]);

  return (
    <EditorSurface toolbar={<Space wrap size={4}>
        <Button
          size="small"
          icon={<BoldOutlined />}
          aria-label={t("editorBold")}
          disabled={disabled || busy}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => execute("bold")}
        />
        <Button
          size="small"
          icon={<ItalicOutlined />}
          aria-label={t("editorItalic")}
          disabled={disabled || busy}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => execute("italic")}
        />
        <Button
          size="small"
          icon={<UnorderedListOutlined />}
          aria-label={t("editorUnorderedList")}
          disabled={disabled || busy}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => execute("insertUnorderedList")}
        />
        <Button
          size="small"
          icon={<OrderedListOutlined />}
          aria-label={t("editorOrderedList")}
          disabled={disabled || busy}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => execute("insertOrderedList")}
        />
        {imageUploadPath && (
          <>
            <Button
              size="small"
              icon={<PictureOutlined />}
              aria-label={imageButtonLabel}
              disabled={disabled || busy || imageBusy}
              loading={imageBusy}
              onMouseDown={(event) => { event.preventDefault(); rememberSelection(); }}
              onClick={pickImage}
            />
            <input
              ref={imageInputRef}
              type="file"
              accept="image/png,image/jpeg"
              className="hidden"
              style={{ display: "none" }}
              tabIndex={-1}
              onChange={(event) => handleImageFiles(event.target.files)}
            />
          </>
        )}
        <Typography.Text type="secondary">{placeholder}</Typography.Text>
        {imageBusy && <Typography.Text type="secondary">{imageUploadingLabel}</Typography.Text>}
        {imageError && <Typography.Text type="danger">{imageUploadError}</Typography.Text>}
      </Space>}>
      <div
        ref={editorRef}
        contentEditable={!disabled}
        suppressContentEditableWarning
        role="textbox"
        aria-multiline="true"
        className="editor-surface-editable rich-text-editor-editable empty:before:text-[var(--ant-color-text-placeholder)] empty:before:content-[attr(data-placeholder)]"
        data-placeholder={placeholder}
        onInput={emit}
        onBlur={emit}
        onKeyUp={rememberSelection}
        onMouseUp={rememberSelection}
        onPaste={(event) => {
          if (!imageUploadPath) return;
          const image = Array.from(event.clipboardData.files).find((file) => file.type.startsWith("image/"));
          if (!image) return;
          event.preventDefault();
          void insertImage(image);
        }}
        onDrop={(event) => {
          if (!imageUploadPath) return;
          const image = Array.from(event.dataTransfer.files).find((file) => file.type.startsWith("image/"));
          if (!image) return;
          event.preventDefault();
          void insertImage(image);
        }}
      />
    </EditorSurface>
  );
}

export function RichTextPreview({ html }: { html: string | null | undefined }) {
  const safe = sanitizeRichText(html ?? "");
  // The value has already passed the backend OWASP policy and the same strict
  // client whitelist above; this boundary prevents untrusted HTML from being
  // inserted directly into the page.
  // nosemgrep: typescript.react.security.audit.react-dangerouslysetinnerhtml.react-dangerouslysetinnerhtml
  return <div className="rich-text-preview" dangerouslySetInnerHTML={{ __html: safe }} />;
}
