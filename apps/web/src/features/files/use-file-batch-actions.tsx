"use client";

import { useEffect, useRef, useState } from "react";
import { App, Button, Space, type TableProps } from "antd";
import { mutate } from "@/platform/api/client";
import { useCenteredModalConfirm } from "@/platform/components/use-centered-modal-confirm";
import { usePreferences } from "@/platform/providers";
import type { MessageKey } from "@/platform/i18n/messages";
import { fileBatchOperations, runFileBatch, selectBatchFiles, type BatchFile, type FileBatchEndpoint, type FileBatchOperation, type FileBatchScope } from "./file-batch";
import { useSessionScope } from "@/platform/auth/use-session-scope";

type BatchActionOptions = {
  endpoint?: FileBatchEndpoint;
  canDelete?: boolean;
  canRestore?: boolean;
  onCleanupStarted?: (id: string) => void;
  onAccepted?: (file: BatchFile, operation: FileBatchOperation) => Promise<void>;
};
const labels = {
  trash: { action: "bulkTrashFiles", confirmation: "bulkTrashConfirm" },
  restore: { action: "bulkRestoreFiles", confirmation: "bulkRestoreConfirm" },
  purge: { action: "bulkPurgeFiles", confirmation: "bulkPurgeConfirm" },
} as const satisfies Record<FileBatchOperation, { action: MessageKey; confirmation: MessageKey }>;

/** Each selected file keeps the same ownership and version checks as its row action. */
export function useFileBatchActions<T extends BatchFile>(items: T[] | undefined, scope: FileBatchScope, refresh: () => Promise<void>, { endpoint = "/account/files", canDelete = true, canRestore = true, onCleanupStarted, onAccepted }: BatchActionOptions = {}) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const confirm = useCenteredModalConfirm();
  const operationSignal = useSessionScope();
  const busy = useRef(false);
  const confirmationOpen = useRef(false);
  const [pending, setPending] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const eligibleState = scope === "files" ? "READY" : "TRASHED";
  const selected = selectBatchFiles(items, selectedIds, scope);
  const operations = fileBatchOperations(scope, selected.length).filter(operation => operation === "restore" ? canRestore : canDelete);

  useEffect(() => {
    const available = new Set((items ?? []).filter(file => file.state === eligibleState && !(scope === "files" && file.protectedByReference)).map(file => file.id));
    setSelectedIds(ids => ids.every(id => available.has(id)) ? ids : ids.filter(id => available.has(id)));
  }, [items, eligibleState, scope]);

  function reset() {
    if (busy.current) return;
    setSelectedIds([]);
  }

  async function run(files: T[], operation: FileBatchOperation, signal: AbortSignal) {
    if (busy.current || signal.aborted) return;
    busy.current = true;
    setPending(true);
    try {
      const batch = await runFileBatch(files, operation, (path, method) => mutate(path, method, undefined, signal), endpoint, async file => {
        if (signal.aborted) return;
        if (operation === "purge") onCleanupStarted?.(file.id);
        await onAccepted?.(file, operation);
      }, signal);
      if (signal.aborted) return;
      const succeeded = batch.accepted.length;
      const failed = batch.failures.length;
      setSelectedIds([]);
      const summary = t("fileBatchPartial", { succeeded, failed });
      if (failed === 0) void message.success(summary);
      else if (succeeded === 0) void message.error(summary);
      else void message.warning(summary);
    } catch (error) {
      if (!signal.aborted) throw error;
    } finally {
      try {
        if (!signal.aborted) await refresh();
      } finally {
        busy.current = false;
        if (!signal.aborted) setPending(false);
      }
    }
  }

  function ask(operation: FileBatchOperation) {
    if (busy.current || confirmationOpen.current || !operations.includes(operation)) return;
    const signal = operationSignal();
    if (signal.aborted) return;
    // Freeze the confirmed records, including versions, before making any writes.
    const files = [...selected];
    confirmationOpen.current = true;
    confirm({
      title: t(labels[operation].confirmation, { count: files.length }),
      content: <ul style={{ maxHeight: 240, overflow: "auto", paddingInlineStart: 20, overflowWrap: "anywhere" }}>{files.map(file => <li key={file.id}>{file.originalName}</li>)}</ul>,
      okText: t(labels[operation].action),
      cancelText: t("cancel"),
      okButtonProps: { danger: operation !== "restore" },
      maskClosable: false,
      afterClose: () => { confirmationOpen.current = false; },
      onOk: () => run(files, operation, signal),
    });
  }

  const rowSelection: TableProps<T>["rowSelection"] = {
    selectedRowKeys: selected.map(file => file.id),
    preserveSelectedRowKeys: false,
    onChange: keys => { if (!busy.current) setSelectedIds(keys.map(String)); },
    getCheckboxProps: file => ({ disabled: pending || operations.length === 0 || file.state !== eligibleState || (scope === "files" && file.protectedByReference === true) }),
  };

  const actions = operations.length > 0 ? <Space wrap className="file-batch-actions">
    {operations.map(operation => <Button key={operation} danger={operation !== "restore"} loading={pending} disabled={pending} onClick={() => ask(operation)}>{t(labels[operation].action)}</Button>)}
  </Space> : null;

  return { rowSelection, actions, feedback: null, pending, reset };
}
