/**
 * 把登录回跳限制为站内单段 dashboard 路径并保留合法查询与锚点，阻止外部地址或异常路径进入恢复流程。
 * Restrict post-login return targets to internal single-segment dashboard paths while preserving valid queries and fragments and rejecting external or malformed destinations.
 */
import { isDashboardSection } from "../routing/dashboard-section.ts";
/**
 * 认证完成后只允许恢复工作台内路径。
 * Only dashboard paths may be restored after authentication.
 */
export function safeReturnPath(value: string | null | undefined): string {
  if (!value || /[\\\r\n]/.test(value)) return "/dashboard/home";
  try {
    const url = new URL(value, "https://lumira.invalid");
    if (url.origin !== "https://lumira.invalid" || !url.pathname.startsWith("/dashboard/") || !isDashboardSection(url.pathname.slice("/dashboard/".length))) return "/dashboard/home";
    return url.pathname + url.search + url.hash;
  } catch { return "/dashboard/home"; }
}

/**
 * 在公开认证页面之间切换时，保留选定的登录后目标地址。
 * Keep the selected post-login destination while moving between public auth pages.
 */
export function authRouteWithReturnTo(path: string, search: string): string {
  const returnTo = new URLSearchParams(search).get("returnTo");
  if (returnTo === null) return path;
  const query = new URLSearchParams({ returnTo });
  return `${path}?${query.toString()}`;
}
