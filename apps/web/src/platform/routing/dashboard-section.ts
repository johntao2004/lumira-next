/**
 * 独立校验 dashboard 单段键的格式，不判定页面存在或授权，使认证回跳无需依赖业务导航清单。
 * Validate dashboard segment syntax independently of page existence or authorization so authentication redirects need not depend on the feature navigation catalog.
 */
/**
 * 由服务端菜单树提供的安全单路径段工作台键。
 * Safe single-segment dashboard keys supplied by the server-owned menu tree.
 */
export function isDashboardSection(value: string): boolean {
  return /^[a-z][a-z0-9-]{0,63}$/.test(value);
}

