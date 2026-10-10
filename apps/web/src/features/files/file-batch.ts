export type FileBatchScope = "files" | "trash";
export type FileBatchOperation = "trash" | "restore" | "purge";
export type FileBatchEndpoint = "/account/files" | "/platform/files";
export type BatchFile = { id: string; originalName: string; version: number; state: string; protectedByReference?: boolean };

export function fileBatchOperations(scope: FileBatchScope, selectedCount: number): FileBatchOperation[] {
  if (selectedCount === 0) return [];
  return scope === "files" ? ["trash"] : ["restore", "purge"];
}

export function selectBatchFiles<T extends BatchFile>(items: T[] | undefined, ids: string[], scope: FileBatchScope): T[] {
  const state = scope === "files" ? "READY" : "TRASHED";
  return (items ?? []).filter(file => ids.includes(file.id) && file.state === state && !(scope === "files" && file.protectedByReference));
}

/** Each request preserves the selected record version; one failure does not hide other results. */
export async function runFileBatch<T extends BatchFile>(
  files: T[],
  operation: FileBatchOperation,
  write: (path: string, method: "POST" | "DELETE") => Promise<unknown>,
  endpoint: FileBatchEndpoint = "/account/files",
  onAccepted?: (file: T) => Promise<void> | void,
  signal?: AbortSignal,
) {
  const accepted: T[] = [];
  const failures: { file: T; error: unknown }[] = [];
  const refreshFailures: { file: T; error: unknown }[] = [];
  for (const file of files) {
    signal?.throwIfAborted();
    const suffix = operation === "restore" ? "/restore" : operation === "purge" ? "/purge" : "";
    try {
      await write(`${endpoint}/${file.id}${suffix}?version=${file.version}`, operation === "restore" ? "POST" : "DELETE");
      accepted.push(file);
    } catch (error) {
      if (signal?.aborted) throw error;
      failures.push({ file, error });
      continue;
    }
    signal?.throwIfAborted();
    try {
      await onAccepted?.(file);
    } catch (error) {
      if (signal?.aborted) throw error;
      // Refresh failures must not turn an already committed server write into a failed file operation.
      refreshFailures.push({ file, error });
    }
    signal?.throwIfAborted();
  }
  return { accepted, failures, refreshFailures };
}
