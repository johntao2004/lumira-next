"use client";

import { useEffect, useRef, useState } from "react";
import { Alert, App, Button, Space, Typography, type TableProps } from "antd";
import { ApiError, mutate } from "@/platform/api/client";
import { messageKeyFor } from "@/platform/api/errors";
import { useCenteredModalConfirm } from "@/platform/components/use-centered-modal-confirm";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";

type BatchFile = { id: string; originalName: string; version: number; state: string };
type Failure = { id: string; name: string; reason: MessageKey };
const errorKeys = ["staleRecord", "forbidden", "fileQuotaExceeded"] as const satisfies readonly MessageKey[];

/** Each selected file keeps the same ownership and version checks as its row action. */
export function useFileBatchActions<T extends BatchFile>(items: T[] | undefined, operation: "trash" | "restore", refresh: () => Promise<void>) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const confirm = useCenteredModalConfirm();
  const busy = useRef(false);
  const confirmationOpen = useRef(false);
  const [pending, setPending] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [result, setResult] = useState<{ succeeded: number; failures: Failure[] } | null>(null);
  const eligibleState = operation === "trash" ? "READY" : "TRASHED";
  const selected = (items ?? []).filter(file => selectedIds.includes(file.id) && file.state === eligibleState);

  useEffect(() => {
    const available = new Set((items ?? []).filter(file => file.state === eligibleState).map(file => file.id));
    setSelectedIds(ids => ids.every(id => available.has(id)) ? ids : ids.filter(id => available.has(id)));
  }, [items, eligibleState]);

  function reset() {
    if (busy.current) return;
    setSelectedIds([]);
    setResult(null);
  }

  async function run(files: T[]) {
    if (busy.current) return;
    busy.current = true;
    setPending(true);
    setResult(null);
    const failures: Failure[] = [];
    try {
      for (const file of files) {
        try {
          const suffix = operation === "restore" ? "/restore" : "";
          await mutate(`/account/files/${file.id}${suffix}?version=${file.version}`, operation === "restore" ? "POST" : "DELETE");
        } catch (error) {
          const mapped = error instanceof ApiError && (error.message === "fileStateConflict" || error.status === 404) ? "staleRecord" : error;
          failures.push({ id: file.id, name: file.originalName, reason: messageKeyFor(mapped, errorKeys) });
        }
      }
      const succeeded = files.length - failures.length;
      setResult({ succeeded, failures });
      setSelectedIds(failures.map(file => file.id));
      if (failures.length === 0) void message.success(t("fileBatchSuccess", { count: succeeded }));
    } finally {
      try { await refresh(); }
      finally { busy.current = false; setPending(false); }
    }
  }

  function ask() {
    if (busy.current || confirmationOpen.current || selected.length === 0) return;
    // Freeze the confirmed records, including versions, before making any writes.
    const files = [...selected];
    confirmationOpen.current = true;
    confirm({
      title: t(operation === "trash" ? "bulkTrashConfirm" : "bulkRestoreConfirm", { count: files.length }),
      content: <ul style={{ maxHeight: 240, overflow: "auto", paddingInlineStart: 20, overflowWrap: "anywhere" }}>{files.map(file => <li key={file.id}>{file.originalName}</li>)}</ul>,
      okText: t(operation === "trash" ? "bulkTrashFiles" : "bulkRestoreFiles"),
      cancelText: t("cancel"),
      okButtonProps: { danger: operation === "trash" },
      maskClosable: false,
      afterClose: () => { confirmationOpen.current = false; },
      onOk: () => run(files),
    });
  }

  const rowSelection: TableProps<T>["rowSelection"] = {
    selectedRowKeys: selected.map(file => file.id),
    preserveSelectedRowKeys: false,
    onChange: keys => { if (!busy.current) setSelectedIds(keys.map(String)); },
    getCheckboxProps: file => ({ disabled: pending || file.state !== eligibleState }),
  };

  const actions = <Space wrap>
    <Typography.Text>{t("selectedFiles", { count: selected.length })}</Typography.Text>
    <Button onClick={reset} disabled={pending || selected.length === 0}>{t("clearFileSelection")}</Button>
    <Button danger={operation === "trash"} loading={pending} disabled={pending || selected.length === 0} onClick={ask}>{t(operation === "trash" ? "bulkTrashFiles" : "bulkRestoreFiles")}</Button>
  </Space>;

  const feedback = result && result.failures.length > 0 ? <Alert
    type={result.succeeded > 0 ? "warning" : "error"}
    showIcon
    message={t("fileBatchPartial", { succeeded: result.succeeded, failed: result.failures.length })}
    description={<ul style={{ maxHeight: 240, overflow: "auto", paddingInlineStart: 20, marginBlock: 0, overflowWrap: "anywhere" }}>{result.failures.map(file => <li key={file.id}>{file.name}: {t(file.reason)}</li>)}</ul>}
    style={{ marginBlockEnd: 16 }}
  /> : null;

  return { rowSelection, actions, feedback, pending, reset };
}
