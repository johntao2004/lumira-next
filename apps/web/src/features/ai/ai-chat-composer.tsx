import { useEffect, useImperativeHandle, useRef, useState, type ComponentRef, type Ref } from "react";
import { Attachments, Sender } from "@ant-design/x";
import { PaperClipOutlined } from "@ant-design/icons";
import { Alert, App, Button, Select, Tooltip, Upload } from "antd";
import { api, apiBlob, upload } from "@/platform/api/client";
import { useSessionScope } from "@/platform/auth/use-session-scope";
import { currentSessionRevision } from "@/platform/auth/session-lifecycle";
import { messageKeyFor } from "@/platform/api/errors";
import { usePreferences } from "@/platform/providers";
import type { ChatAttachment, ModelOption, Provider } from "./types";

const accept = ".txt,.md,.markdown,.doc,.docx,.pdf,.csv,.json,.log,.png,.jpg,.jpeg";
type PendingFile = { uid: string; name: string; size: number; status: "uploading" | "error" };
export type AiChatComposerRef = { focus: () => void };
type Props = {
  ref?: Ref<AiChatComposerRef>;
  draft: string; onChange: (value: string) => void; onSubmit: (value: string) => void; onCancel: () => void;
  files: ChatAttachment[]; onAttach: (file: ChatAttachment) => void; onRemove: (id: string) => void;
  onUploadingChange: (value: boolean) => void;
  providers: Provider[]; models: ModelOption[]; providerId?: string; modelName?: string;
  onProviderChange: (id: string) => void; onModelChange: (name: string) => void;
  providerDisabled: boolean; modelDisabled: boolean; modelLoading: boolean;
  busy: boolean; disabled: boolean; uploadDisabled: boolean; supportsVision: boolean;
};

export function AiChatComposer(props: Props) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const sessionScope = useSessionScope();
  const attachments = useRef<ComponentRef<typeof Attachments>>(null);
  const sender = useRef<ComponentRef<typeof Sender>>(null);
  useImperativeHandle(props.ref, () => ({ focus: () => sender.current?.focus({ cursor: "end" }) }), []);
  const [pending, setPending] = useState<PendingFile[]>([]);
  const [thumbUrls, setThumbUrls] = useState<Record<string, string>>({});
  const uploadingCount = useRef(0);
  const reservedCount = useRef(props.files.length);
  reservedCount.current = props.files.length + pending.filter(file => file.status === "uploading").length;
  const uploading = pending.some(file => file.status === "uploading");
  const visionRequired = props.files.some(file => file.image) && !props.supportsVision && !props.modelLoading && !!props.modelName;
  const canSend = !props.busy && !props.disabled && pending.length === 0 && !visionRequired && (!!props.draft.trim() || props.files.length > 0);
  const imageFileKey = props.files.filter(file => file.image).map(file => file.id).join("\0");

  useEffect(() => {
    const ids = imageFileKey ? imageFileKey.split("\0") : [];
    const lifetime = new AbortController();
    const signal = AbortSignal.any([sessionScope(), lifetime.signal]);
    const revision = currentSessionRevision();
    const created: string[] = [];
    let active = true;
    void Promise.all(ids.map(async id => {
      try {
        const blob = await apiBlob(`/account/files/${id}/content`, { signal });
        signal.throwIfAborted();
        if (revision !== currentSessionRevision()) return null;
        const url = URL.createObjectURL(blob);
        created.push(url);
        return [id, url] as const;
      } catch {
        return null;
      }
    })).then(results => {
      if (!active || revision !== currentSessionRevision()) {
        created.forEach(url => URL.revokeObjectURL(url));
        return;
      }
      setThumbUrls(Object.fromEntries(results.filter((entry): entry is readonly [string, string] => entry !== null)));
    });
    return () => {
      active = false;
      lifetime.abort();
      created.forEach(url => URL.revokeObjectURL(url));
    };
  }, [imageFileKey, sessionScope]);

  async function attach(file: File) {
    if (props.uploadDisabled) return;
    if (!/\.(txt|md|markdown|docx?|pdf|csv|json|log|png|jpe?g)$/i.test(file.name)) {
      void message.error(t("aiAttachmentUnsupported")); return;
    }
    if (file.size > 5 * 1024 * 1024) { void message.error(t("aiAttachmentTooLarge")); return; }
    if (reservedCount.current >= 4) { void message.error(t("aiAttachmentLimit")); return; }
    const uid = crypto.randomUUID();
    const signal = sessionScope();
    reservedCount.current++;
    uploadingCount.current++;
    props.onUploadingChange(true);
    setPending(current => [...current, { uid, name: file.name, size: file.size, status: "uploading" }]);
    try {
      const stored = await upload<{ id: string }>(`/account/files?filename=${encodeURIComponent(file.name)}`, file, signal);
      signal.throwIfAborted();
      const prepared = await api<ChatAttachment>(`/platform/ai/attachments/${stored.id}`, { signal });
      signal.throwIfAborted();
      props.onAttach(prepared);
      setPending(current => current.filter(item => item.uid !== uid));
    } catch (error) {
      if (!signal.aborted) {
        setPending(current => current.map(item => item.uid === uid ? { ...item, status: "error" } : item));
        void message.error(t(messageKeyFor(error, ["aiAttachmentUnsupported", "aiAttachmentTooLarge", "aiAttachmentEmpty", "aiAttachmentInvalid", "fileQuotaExceeded", "uploadRateLimited"], "unavailable")));
      }
    } finally {
      uploadingCount.current--;
      if (!signal.aborted) props.onUploadingChange(uploadingCount.current > 0);
    }
  }

  return <Sender
    ref={sender}
    className="ai-chat-sender"
    styles={{ input: { outline: "none" } }}
    value={props.draft}
    onChange={props.onChange}
    onSubmit={value => { if (canSend) props.onSubmit(value); }}
    onCancel={props.onCancel}
    loading={props.busy}
    disabled={props.uploadDisabled && !props.busy}
    readOnly={props.busy}
    placeholder={t("aiChatPlaceholder")}
    autoSize={{ minRows: 2, maxRows: 6 }}
    suffix={false}
    onPasteFile={files => { for (const file of Array.from(files)) attachments.current?.upload(file); }}
    header={<Sender.Header title={t("aiAttachments")} open={props.files.length > 0 || pending.length > 0} closable={false} forceRender>
      <Attachments
        ref={attachments}
        className="ai-chat-attachments"
        accept={accept}
        multiple
        maxCount={4}
        disabled={props.uploadDisabled || uploading}
        items={[
          ...props.files.map(file => ({ uid: file.id, name: file.name, size: file.sizeBytes, status: "done" as const,
            thumbUrl: file.image ? thumbUrls[file.id] : undefined,
            description: t(file.truncated ? "aiAttachmentTruncated" : file.image ? "aiAttachmentImage" : "aiAttachmentText"),
          })),
          ...pending.map(file => ({ ...file, cardType: "file" as const,
            description: file.status === "error" ? t("unavailable") : t("loading"),
          })),
        ]}
        overflow="scrollX"
        getDropContainer={() => props.uploadDisabled ? null : sender.current?.nativeElement}
        beforeUpload={file => { void attach(file); return Upload.LIST_IGNORE; }}
        onRemove={file => {
          if (file.status === "uploading") return false;
          props.onRemove(file.uid);
          setPending(current => current.filter(item => item.uid !== file.uid));
          return false;
        }}
      />
      {visionRequired && <Alert type="warning" showIcon title={t("aiAttachmentVisionRequired")} />}
    </Sender.Header>}
    footer={(_, { components: { SendButton, LoadingButton } }) => <>
      <div className="ai-chat-composer-tools">
        <Tooltip title={t("aiUploadAttachment")}>
          <Button type="text" icon={<PaperClipOutlined />} aria-label={t("aiUploadAttachment")} disabled={props.uploadDisabled || uploading}
            onClick={() => {
              if (reservedCount.current >= 4) { void message.error(t("aiAttachmentLimit")); return; }
              attachments.current?.select({ accept, multiple: true });
            }} />
        </Tooltip>
        <div className="ai-chat-composer-models">
          <Select aria-label={t("aiSelectProvider")} value={props.providerId} onChange={props.onProviderChange} placeholder={t("aiSelectProvider")}
            disabled={props.providerDisabled} options={props.providers.map(provider => ({ value: provider.id, label: provider.name, disabled: provider.providerKind === "TYPESAFE_JEV" }))} />
          <Select aria-label={t("aiSelectModel")} value={props.modelName} onChange={props.onModelChange} placeholder={t("aiSelectModel")}
            disabled={props.modelDisabled} loading={props.modelLoading}
            options={props.models.map(model => ({ value: model.id, label: `${model.name}${model.inputModalities.includes("image") ? ` · ${t("aiAttachmentImage")}` : ""}` }))} />
        </div>
        {props.busy ? <LoadingButton /> : <SendButton disabled={!canSend} />}
      </div>
    </>}
  />;
}
