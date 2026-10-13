/** Defers challenge loads to cancellable microtasks scoped to action/attempt/locale. */
export function createCaptchaPanelAutoLoader() {
  let contextKey = null;
  let revision = 0;
  let deferredStart = null;
  let deferredRevision = 0;

  function cancelDeferredStart() {
    deferredStart = null;
    deferredRevision += 1;
  }

  return {
    schedule(context, load) {
      const nextContextKey = JSON.stringify([
        context.action,
        context.attemptId ?? null,
        context.locale,
      ]);
      if (nextContextKey !== contextKey) {
        contextKey = nextContextKey;
        revision += 1;
        cancelDeferredStart();
      }

      const scheduledRevision = revision;
      let cancelled = false;
      queueMicrotask(() => {
        if (!cancelled && scheduledRevision === revision) load();
      });
      return () => {
        cancelled = true;
        cancelDeferredStart();
      };
    },
    defer(load) {
      cancelDeferredStart();
      deferredStart = load;
    },
    flush() {
      const load = deferredStart;
      deferredStart = null;
      if (!load) return;

      const scheduledRevision = ++deferredRevision;
      const scheduledContext = revision;
      queueMicrotask(() => {
        if (scheduledRevision === deferredRevision && scheduledContext === revision) load();
      });
    },
    cancel() {
      revision += 1;
      cancelDeferredStart();
    },
  };
}
