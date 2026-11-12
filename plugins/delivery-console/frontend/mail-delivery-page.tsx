"use client";

import type { MessageKey } from "@/platform/i18n/messages";
import { DeliveryTable } from "./delivery-table";

const states = {
  PENDING: "mailPending",
  LEASED: "mailLeased",
  SENDING: "mailSending",
  ACCEPTED: "mailAccepted",
  FAILED: "mailFailed",
  UNCERTAIN: "mailUncertain",
  EXPIRED: "mailExpired",
} as const satisfies Record<string, MessageKey>;

const results: Record<string, MessageKey> = {
  ACCEPTED: "mailAccepted",
  AUTHENTICATION_FAILED: "mailAuthenticationFailed",
  CONNECTION_FAILED: "mailConnectionFailed",
  DELIVERY_UNCERTAIN: "mailUncertain",
  CONFIGURATION_UNAVAILABLE: "mailConfigurationUnavailable",
  LEASE_EXPIRED: "mailLeaseExpired",
};

export function MailDeliveryPage() {
  return <DeliveryTable
    endpoint="/platform/mail-deliveries"
    queryKey="mail-deliveries"
    states={states}
    results={results}
    labels={{ hint: "mailDeliveryHint", attempts: "mailAttempts", lastResult: "mailLastResult", scheduled: "mailScheduled", expires: "mailExpires" }}
  />;
}
