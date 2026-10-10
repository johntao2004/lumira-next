import { useState } from "react";
import { useSubmitAction, type SubmitOptions } from "./use-submit-action";

/** undefined = closed, null = create, T = edit. Business validation stays in the caller. */
export function useCrudDrawer<T>(options: SubmitOptions = {}) {
  const [selected, select] = useState<T | null | undefined>();
  const action = useSubmitAction(options);
  const reset = () => { select(undefined); action.setError(null); };
  return {
    ...action,
    selected,
    setSelected: (value: T | null | undefined) => {
      if (action.isPending()) return;
      action.setError(null);
      select(value);
    },
    close: () => { if (!action.isPending()) reset(); },
    // Used after the write has committed; refresh can still be pending.
    saved: reset,
  };
}
