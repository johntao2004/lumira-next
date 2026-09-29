export class ApiError extends Error {
  status: number;
  issues: {row:number;field:string;reason:string}[];
  constructor(
    status: number,
    message: string,
    issues: {row:number;field:string;reason:string}[] = [],
  ) {
    super(message);
    this.status = status;
    this.issues = issues;
  }
}
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...init,
    credentials: "same-origin",
    cache: "no-store",
    signal: init.signal ?? AbortSignal.timeout(15_000),
  });
  if (!response.ok) {
    let issues: {row:number;field:string;reason:string}[] = [];
    // Keep transport errors locale-neutral. Pages map stable API error codes
    // through the shared message catalog instead of leaking one language from
    // the low-level client.
    let message = "unavailable";
    try {
      const body = await response.json();
      message = body.detail ?? body.message ?? message;
      if(Array.isArray(body.issues))issues=body.issues.slice(0,100).filter((item:unknown):item is {row:number;field:string;reason:string}=>{
        if(!item||typeof item!=="object")return false;
        const value=item as Record<string,unknown>;
        return Number.isInteger(value.row)&&Number(value.row)>0&&typeof value.field==="string"&&typeof value.reason==="string";
      });
    } catch {}
    if (response.status === 401 && path !== "/auth/login" && typeof window !== "undefined") {
      window.dispatchEvent(new Event("lumira:session-expired"));
    }
    if(message==="moduleUnavailable"&&typeof window!=="undefined")window.dispatchEvent(new Event("lumira:modules-changed"));
    throw new ApiError(response.status, message, issues);
  }
  if (response.status === 204) return undefined as T;
  const body = await response.text();
  return body ? JSON.parse(body) as T : undefined as T;
}
export async function csrf(signal?: AbortSignal) {
  return api<{ headerName: string; token: string }>("/auth/csrf", { signal });
}
/** Binary writes share authentication, CSRF and API error handling with JSON mutations. */
export async function upload<T>(path: string, file: Blob): Promise<T> {
  const token = await csrf();
  return api<T>(path, { method: "POST", headers: { "Content-Type": "application/octet-stream", [token.headerName]: token.token }, body: file });
}
export async function login(username: string, password: string) {
  const token = await csrf();
  return api("/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      [token.headerName]: token.token,
    },
    body: new URLSearchParams({ username, password }),
  });
}
export async function logout() {
  const token = await csrf();
  return api<void>("/auth/logout", {
    method: "POST",
    headers: { [token.headerName]: token.token },
  });
}
/** Shared write transport: all platform mutations obtain a fresh CSRF token. */
export async function mutate<T>(path: string, method: "POST" | "PUT" | "PATCH" | "DELETE", data?: unknown, signal?: AbortSignal): Promise<T> {
  const requestSignal = signal ? AbortSignal.any([signal, AbortSignal.timeout(15_000)]) : undefined;
  const token = await csrf(requestSignal);
  return api<T>(path, { method, headers: { "Content-Type": "application/json", [token.headerName]: token.token }, body: data === undefined ? undefined : JSON.stringify(data), signal: requestSignal });
}
