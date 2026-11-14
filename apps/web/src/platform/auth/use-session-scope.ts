/**
 * 把异步后续操作绑定到起始账号代际和组件生命周期，换号或卸载后用取消信号阻止旧操作继续影响界面。
 * Bind asynchronous continuations to their starting account epoch and component lifetime, using cancellation signals to prevent stale UI effects after account changes or unmounting.
 */
"use client";

import { useCallback, useEffect, useRef } from "react";
import { sessionSignal } from "./session-lifecycle";

/**
 * 将异步后续操作绑定到启动时的账户代次与组件存活期。
 * Bind an async continuation to its starting account epoch and component lifetime.
 */
export function useSessionScope() {
  const origin = useRef(sessionSignal());
  const lifetime = useRef(new AbortController());
  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    return () => controller.abort();
  }, []);
  return useCallback(
    () => AbortSignal.any([origin.current, lifetime.current.signal]),
    [],
  );
}
