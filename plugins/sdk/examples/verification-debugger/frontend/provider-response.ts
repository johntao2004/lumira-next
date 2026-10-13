import type { MockProvider } from "./types";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function isProviderSendSuccessful(provider: MockProvider, httpStatus: number, body: unknown): boolean {
  if (httpStatus < 200 || httpStatus >= 300 || !isRecord(body)) return false;

  if (provider === "aliyun") return body.Code === "OK";

  const response = body.Response;
  if (!isRecord(response) || response.Error != null) return false;
  const sendStatusSet = response.SendStatusSet;
  return Array.isArray(sendStatusSet)
    && sendStatusSet.length > 0
    && sendStatusSet.every(status => isRecord(status) && status.Code === "Ok");
}
