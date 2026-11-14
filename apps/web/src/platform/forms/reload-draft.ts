/** 用户确认后才丢弃草稿，读取失败或会话取消时保留原输入。
 * Discard a draft only after confirmation and a successful read; failures or cancellation preserve input. */
export async function reloadDraft<T>(signal: AbortSignal, read: () => Promise<{ data?: T }>, reset: (fresh: T) => void): Promise<void> {
  if (signal.aborted) return;
  const fresh = await read();
  if (!signal.aborted && fresh.data !== undefined) reset(fresh.data);
}
