import type { ChatShareReference } from "./chat-history";
import type { AiChatShareSnapshot } from "./chat-share";

export type ChatShareSyncEntry = { sessionKey: string; share: ChatShareReference; snapshot: AiChatShareSnapshot };
type SyncState = { entry: ChatShareSyncEntry; desired: string; acknowledged?: string; running: boolean; controller: AbortController };

/** Serialize writes per link and coalesce pending changes so an older reply cannot win. */
export function createChatShareSyncQueue(options: {
  write: (entry: ChatShareSyncEntry, signal: AbortSignal) => Promise<void>;
  onFailure: (entry: ChatShareSyncEntry, error: unknown) => void;
  onSuccess: (entry: ChatShareSyncEntry) => void;
}) {
  const states = new Map<string, SyncState>();
  let disposed = false;
  const active = (state: SyncState) => !disposed && states.get(state.entry.share.id) === state;
  const run = async (state: SyncState) => {
    if (state.running || !active(state)) return;
    state.running = true;
    try {
      while (active(state) && state.desired !== state.acknowledged) {
        const entry = state.entry;
        const attempted = state.desired;
        try {
          await options.write(entry, state.controller.signal);
          if (!active(state)) return;
          state.acknowledged = attempted;
          options.onSuccess(entry);
        } catch (error) {
          if (active(state) && !state.controller.signal.aborted) options.onFailure(entry, error);
          return;
        }
      }
    } finally { state.running = false; }
  };
  return {
    reconcile(entries: ChatShareSyncEntry[]) {
      if (disposed) return;
      const ids = new Set(entries.map(entry => entry.share.id));
      for (const [id, state] of states) if (!ids.has(id)) { states.delete(id); state.controller.abort(); }
      for (const entry of entries) {
        const signature = JSON.stringify(entry.snapshot);
        const current = states.get(entry.share.id);
        if (current?.desired === signature) continue;
        const state = current ?? { entry, desired: signature, running: false, controller: new AbortController() };
        state.entry = entry;
        state.desired = signature;
        states.set(entry.share.id, state);
        void run(state);
      }
    },
    acknowledge(entry: ChatShareSyncEntry) {
      if (disposed) return;
      const signature = JSON.stringify(entry.snapshot);
      states.set(entry.share.id, { entry, desired: signature, acknowledged: signature, running: false, controller: new AbortController() });
    },
    retry() { for (const state of states.values()) void run(state); },
    dispose() { disposed = true; for (const state of states.values()) state.controller.abort(); states.clear(); },
  };
}
