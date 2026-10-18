export type FileBatchScope = "files" | "trash";
export type FileBatchOperation = "trash" | "restore" | "purge";
export type FileBatchEndpoint = "/account/files" | "/platform/files";
export type BatchFile = { id: string; originalName: string; version: number; state: string; protectedByReference?: boolean };

export type FileBatchPermissions = { canDelete?: boolean; canRestore?: boolean };

function fileBatchOperationsForScope(scope: FileBatchScope): FileBatchOperation[] {
  return scope === "files" ? ["trash"] : ["restore", "purge"];
}

export function fileBatchOperations(scope: FileBatchScope, selectedCount: number): FileBatchOperation[] {
  return selectedCount === 0 ? [] : fileBatchOperationsForScope(scope);
}

/** Operations available in the current scope, independent of the current selection. */
export function permittedFileBatchOperations(scope: FileBatchScope, { canDelete = true, canRestore = true }: FileBatchPermissions = {}): FileBatchOperation[] {
  return fileBatchOperationsForScope(scope).filter(operation => operation === "restore" ? canRestore : canDelete);
}

/** Rows can be selected when their state and at least one permitted bulk action allow it. */
export function canSelectFileForBatch(file: BatchFile, scope: FileBatchScope, permissions: FileBatchPermissions = {}): boolean {
  const eligibleState = scope === "files" ? "READY" : "TRASHED";
  return permittedFileBatchOperations(scope, permissions).length > 0
    && file.state === eligibleState
    && !(scope === "files" && file.protectedByReference === true);
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
