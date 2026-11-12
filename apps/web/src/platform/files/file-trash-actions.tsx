"use client";

import { useState } from "react";
import { App, Button } from "antd";
import { api, ApiError, mutate } from "@/platform/api/client";
import { DeleteAction } from "@/platform/components/delete-action";
import { usePreferences } from "@/platform/providers";

export type TrashFile = { id: string; originalName: string; version: number };

export function FileTrashActions({ file, endpoint, onChanged, onCleanupStarted, disabled = false, canRestore = true, canDelete = true }: {
  file: TrashFile;
  endpoint: string;
  onChanged: () => Promise<void>;
  onCleanupStarted?: (id: string) => void;
  disabled?: boolean;
  canRestore?: boolean;
  canDelete?: boolean;
}) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const [restoring, setRestoring] = useState(false);

  async function restore() {
    if (restoring || disabled) return;
    setRestoring(true);
    try {
      await mutate(`${endpoint}/${file.id}/restore?version=${file.version}`, "POST");
      await onChanged();
    } catch (error) {
      const key = error instanceof ApiError && error.message === "fileStateConflict" ? "staleRecord" : "unavailable";
      void message.error(t(key));
    } finally {
      setRestoring(false);
    }
  }

  return <>
    {canRestore && <Button type="link" disabled={disabled} loading={restoring} onClick={() => void restore()}>{t("restoreFile")}</Button>}
    {canDelete && <DeleteAction
      name={file.originalName}
      actionLabel={t("purgeFile")}
      confirmation={t("purgeFileConfirm", { name: file.originalName })}
      disabled={disabled || restoring}
      onDelete={async () => {
        try {
          await mutate(`${endpoint}/${file.id}/purge?version=${file.version}`, "DELETE");
          onCleanupStarted?.(file.id);
        } finally {
          await onChanged();
        }
      }}
    />}
  </>;
}
