import { useEffect, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { captchaRequiresPreflight } from "./captcha-policy";
import type { CaptchaAction } from "./api-types";
import {
  createCaptchaProofController,
  type CaptchaProofController,
  type CaptchaProofOutcome,
} from "./captcha-runtime.mjs";

export type UseCaptchaProofOptions = {
  alwaysActions?: readonly string[] | null;
  supported: boolean;
  unavailableMessage: string;
  /** Some legacy callers intentionally recognize captchaRequired from any Error. */
  isCaptchaRequired?: (cause: unknown) => boolean;
};

/** Shares proof prompting and one required-captcha retry while leaving requests in the caller. */
export function useCaptchaProof(options: UseCaptchaProofOptions) {
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const [action, setAction] = useState<CaptchaAction | null>(null);
  const controllerRef = useRef<CaptchaProofController | null>(null);
  const activeRef = useRef(true);

  const createController = () => createCaptchaProofController(next => {
    if (activeRef.current) setAction(next);
  });
  if (!controllerRef.current) controllerRef.current = createController();

  useEffect(() => {
    activeRef.current = true;
    if (!controllerRef.current) controllerRef.current = createController();
    const owner = controllerRef.current;
    return () => {
      activeRef.current = false;
      owner.dispose();
      if (controllerRef.current === owner) controllerRef.current = null;
    };
  }, []);

  async function run<T>(
    actionName: CaptchaAction,
    operation: (proofId?: string) => Promise<T>,
  ): Promise<CaptchaProofOutcome<T>> {
    if (!activeRef.current || !controllerRef.current) return { status: "cancelled" };
    return controllerRef.current.run(actionName, {
      shouldPreflight: requestedAction => captchaRequiresPreflight(requestedAction, optionsRef.current.alwaysActions),
      get supported() { return optionsRef.current.supported; },
      unavailableError: () => new ApiError(503, optionsRef.current.unavailableMessage),
      isCaptchaRequired: cause => optionsRef.current.isCaptchaRequired?.(cause)
        ?? (cause instanceof ApiError && cause.message === "captchaRequired"),
    }, operation);
  }

  function finish(proof: { action: CaptchaAction; captchaProofId: string } | null) {
    if (!activeRef.current) return false;
    return controllerRef.current?.finish(proof) ?? false;
  }

  function cancel() {
    if (activeRef.current) controllerRef.current?.cancel();
  }

  function isMounted() {
    return activeRef.current && (controllerRef.current?.isMounted() ?? false);
  }

  return { action, run, finish, cancel, isMounted };
}
