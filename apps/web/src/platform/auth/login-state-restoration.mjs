/** Restore a pending public login challenge without accepting stale session work. */
/**
 * @template T
 * @param {{
 *   epoch: {capture: () => number, isCurrent: (revision: number) => boolean},
 *   signal: AbortSignal,
 *   loadLoginState: () => Promise<T>,
 *   loadSecondFactorPending: () => Promise<T>,
 *   onState: (state: T, revision: number) => void,
 * }} options
 */
export async function restorePendingLogin(options) {
  const { epoch, signal, loadLoginState, loadSecondFactorPending, onState } = options;
  // /auth/me may have invalidated the session and synchronously renewed the
  // LoginPage action epoch before this restoration starts.
  let revision = epoch.capture();
  if (signal.aborted) return;

  try {
    const state = await loadLoginState();
    if (!signal.aborted && epoch.isCurrent(revision)) onState(state, revision);
    return;
  } catch (error) {
    if (signal.aborted) return;
    // A 401 from /auth/login/state itself also retires the session. Permit the
    // legacy second-factor fallback only within the epoch that follows it.
    if (!epoch.isCurrent(revision)) {
      if (!error || typeof error !== "object" || error.status !== 401) return;
      revision = epoch.capture();
    }
  }

  if (signal.aborted || !epoch.isCurrent(revision)) return;
  try {
    const state = await loadSecondFactorPending();
    if (signal.aborted || !epoch.isCurrent(revision) || !state?.secondFactorRequired) return;
    onState(state, revision);
  } catch {
    // The login page still returns to its normal sign-in form if no pending
    // second-factor challenge can be restored.
  }
}
