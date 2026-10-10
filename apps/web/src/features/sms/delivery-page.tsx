"use client";

import type { MessageKey } from "@/platform/i18n/messages";
import { DeliveryTable } from "../communication/delivery-table";

const states = {
  PENDING: "smsPending",
  LEASED: "smsLeased",
  SENDING: "smsSending",
  ACCEPTED: "smsAccepted",
  FAILED: "smsFailed",
  UNCERTAIN: "smsUncertain",
  EXPIRED: "smsExpired",
} as const satisfies Record<string, MessageKey>;

const results: Record<string, MessageKey> = {
  ACCEPTED: "smsAccepted",
  AUTHENTICATION_FAILED: "smsAuthenticationFailed",
  PROVIDER_REJECTED: "smsProviderRejected",
  CONNECTION_FAILED: "smsConnectionFailed",
  DELIVERY_UNCERTAIN: "smsDeliveryUncertain",
  CONFIGURATION_UNAVAILABLE: "smsConfigurationUnavailable",
  LEASE_EXPIRED: "smsLeaseExpired",
};

export function SmsDeliveryPage() {
  return <DeliveryTable
    endpoint="/platform/sms-deliveries"
    queryKey="sms-deliveries"
    states={states}
    results={results}
    labels={{ hint: "smsDeliveryHint", attempts: "smsAttempts", lastResult: "smsLastResult", scheduled: "smsScheduled", expires: "smsExpires" }}
  />;
}
