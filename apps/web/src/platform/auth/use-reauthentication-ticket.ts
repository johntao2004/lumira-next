"use client";

import { useState } from "react";
import type { FormInstance } from "antd";
import { mutate } from "../api/client";
import type {
  ReauthenticationAction,
  ReauthenticationStart,
  ReauthenticationTicket,
} from "./api-types";
import { reauthenticationCompletionBody, type ReauthenticationFormValues } from "./reauthentication-contract";

export type ReauthenticationTicketResult =
  | { status: "ready"; ticket: ReauthenticationTicket }
  | { status: "unavailable" | "passkey" | "incomplete" };

export function useReauthenticationTicket<T extends ReauthenticationFormValues>(form: FormInstance<T>) {
  const [challenge, setChallenge] = useState<ReauthenticationStart | null>(null);
  const [ticket, setTicket] = useState<ReauthenticationTicket | null>(null);

  function reset() {
    setChallenge(null);
    setTicket(null);
  }

  async function begin(action: ReauthenticationAction) {
    const nextChallenge = await mutate<ReauthenticationStart>("/account/reauthentication/start", "POST", { action });
    setChallenge(nextChallenge);
    return nextChallenge;
  }

  async function complete(values: T): Promise<ReauthenticationTicketResult> {
    if (ticket) return { status: "ready", ticket };
    if (!challenge) return { status: "incomplete" };
    if (!challenge.methods.length) return { status: "unavailable" };
    const method = values.reauthenticationMethod ?? challenge.methods[0];
    if (method === "PASSKEY") return { status: "passkey" };
    const body = reauthenticationCompletionBody(challenge, values);
    if (!body) return { status: "incomplete" };

    const nextTicket = await mutate<ReauthenticationTicket>("/account/reauthentication/complete", "POST", body);
    setTicket(nextTicket);
    form.setFieldsValue({
      currentPassword: undefined,
      factorCode: undefined,
      reauthenticationCode: undefined,
      reauthenticationCodeChallengeId: undefined,
    } as Partial<T>);
    return { status: "ready", ticket: nextTicket };
  }

  return { challenge, ticket, setTicket, reset, begin, complete };
}
