"use client";
import { useCallback, useEffect, useState } from "react";
import { useSessionScope } from "../auth/use-session-scope";
import { reloadDraft } from "./reload-draft";

/** 固定编辑快照，只有显式保存或确认重载可以替换；后台数据变化不覆盖草稿。
 * Pin an editing snapshot until an explicit save or confirmed reload; background data never replaces a draft. */
export function useSettingsDraft<T>(data: T | undefined, read: () => Promise<{ data?: T }>, reset: () => void) {
  const [state, setState] = useState<{ snapshot: T | null; revision: number }>({ snapshot: null, revision: 0 });
  const { snapshot, revision } = state;
  const commit = useCallback((value: T) => setState(previous => ({ snapshot: value, revision: previous.revision + 1 })), []);
  const operationSignal = useSessionScope();
  useEffect(() => { if (snapshot === null && data !== undefined) commit(data); }, [commit, data, snapshot]);
  const reload = () => reloadDraft(operationSignal(), read, fresh => { reset(); commit(fresh); });
  return { snapshot, revision, commit, reload, operationSignal };
}
