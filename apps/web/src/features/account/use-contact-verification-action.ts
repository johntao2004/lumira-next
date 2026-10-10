"use client";

import type { FormInstance } from "antd";
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError } from "@/platform/api/client";
import type { MessageKey } from "@/platform/i18n/messages";
import { useSubmitAction } from "@/platform/forms/use-submit-action";

type ContactStep = "request" | "confirm";

/** Share request/confirm submission lifetime while leaving channel behavior with each drawer. */
export function useContactVerificationAction({ queryKey, requestForm, errorKeys, onError }: {
  queryKey: readonly unknown[];
  requestForm: FormInstance;
  errorKeys: Readonly<Record<string, MessageKey>>;
  onError?: () => void;
}) {
  const client = useQueryClient();
  const [pendingStep, setPendingStep] = useState<ContactStep | null>(null);
  const action = useSubmitAction({
    mapError: cause => errorKeys[cause instanceof ApiError ? cause.message : ""] ?? "unavailable",
    onError,
  });

  async function run(step: ContactStep, operation: () => Promise<void>) {
    if (action.isPending()) return false;
    setPendingStep(step);
    try {
      return await action.run(async () => {
        let failed = false;
        let failure: unknown;
        try {
          await operation();
        } catch (cause) {
          failed = true;
          failure = cause;
        } finally {
          requestForm.setFieldValue("currentPassword", undefined);
          try {
            await client.invalidateQueries({ queryKey });
          } catch (cause) {
            if (!failed) {
              failed = true;
              failure = cause;
            }
          }
        }
        if (failed) throw failure;
      });
    } finally {
      setPendingStep(null);
    }
  }

  return { action, pendingStep, run };
}
