"use client";

import { App } from "antd";
import { useCallback } from "react";

export function useCenteredModalConfirm() {
  const { modal } = App.useApp();
  return useCallback(
    (config: Parameters<typeof modal.confirm>[0]) =>
      modal.confirm({ ...config, centered: true }),
    [modal],
  );
}
