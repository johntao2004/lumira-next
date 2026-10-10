export type AppErrorKind = "chunk" | "asset" | "network" | "http" | "render";

export interface AppErrorDiagnostic {
  number: string;
  code: string;
  kind: AppErrorKind;
  page: string;
  component: string;
  errorType: string;
  detail: string;
  resource?: string;
  occurredAt: string;
}

function text(value: unknown, limit = 500): string {
  return typeof value === "string" ? value.replace(/[\u0000-\u001f]/g, " ").trim().slice(0, limit) : "";
}

/** Diagnostic links omit credentials, query values and fragments. */
function resourcePath(value: string): string | undefined {
  try {
    const url = new URL(value, "http://lumira.invalid");
    return /^https?:$/.test(url.protocol) ? text(url.pathname) : undefined;
  } catch { return undefined; }
}

export function errorComponent(componentStack?: string | null): string {
  return text(componentStack?.match(/\bat\s+([^\s(]+)/)?.[1], 120);
}

export function describeAppError(error: unknown, page: string, now = new Date()): AppErrorDiagnostic {
  const value = error && typeof error === "object" ? error as Record<string, unknown> : {};
  const name = text(value.name, 80) || "Error";
  const message = text(value.message ?? (typeof error === "string" ? error : ""));
  const request = text(value.request) || message.match(/https?:\/\/[^\s)]+/)?.[0];
  const resource = request ? resourcePath(request) : undefined;
  let kind: AppErrorKind = "render";
  let code = "WEB_PAGE_RENDER_FAILED";
  if (name === "ChunkLoadError" || /Loading (?:CSS )?chunk|dynamically imported module|Importing a module script failed/i.test(message)) {
    kind = "chunk";
    code = "WEB_CHUNK_LOAD_FAILED";
  } else if (name === "SyntaxError" && /Unexpected token.*</i.test(message)) {
    kind = "asset";
    code = "WEB_ASSET_RESPONSE_INVALID";
  } else if (Number.isInteger(value.status) && Number(value.status) >= 400 && Number(value.status) <= 599) {
    kind = "http";
    code = `HTTP_${value.status}`;
  } else if (/Failed to fetch|NetworkError|Load failed|fetch failed/i.test(message)) {
    kind = "network";
    code = "WEB_NETWORK_FAILED";
  }
  const suffix = globalThis.crypto.randomUUID().slice(0, 8).toUpperCase();
  return {
    number: `ERR-${now.toISOString().slice(0, 10).replaceAll("-", "")}-${suffix}`,
    code,
    kind,
    page: resourcePath(page) ?? "/",
    component: "",
    errorType: name,
    detail: message.replace(/https?:\/\/[^\s)]+/g, match => resourcePath(match) ?? "[resource]"),
    resource,
    occurredAt: now.toISOString(),
  };
}
