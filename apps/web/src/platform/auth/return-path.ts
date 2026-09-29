import { isDashboardSection } from "../shell/routes.ts";
/** Only dashboard paths may be restored after authentication. */
export function safeReturnPath(value: string | null | undefined): string {
  if (!value || /[\\\r\n]/.test(value)) return "/dashboard/home";
  try {
    const url = new URL(value, "https://lumira.invalid");
    if (url.origin !== "https://lumira.invalid" || !url.pathname.startsWith("/dashboard/") || !isDashboardSection(url.pathname.slice("/dashboard/".length))) return "/dashboard/home";
    return url.pathname + url.search + url.hash;
  } catch { return "/dashboard/home"; }
}
