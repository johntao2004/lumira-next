/**
 * 将服务端授权事实稳定序列化为缓存标识以识别变化并隔离导航缓存，这里的 signature 不是密码学签名或授权凭证。
 * Serialize server authorization facts into a stable cache key for change detection and navigation isolation; this signature is neither a cryptographic signature nor an authorization credential.
 */
export interface AuthorizationSignatureInput {
  userId?: string | null;
  authorities?: readonly string[] | null;
  globalAuthorities?: readonly string[] | null;
  enabledModules?: readonly string[] | null;
  authorizationRevision?: string | null;
  authorizationContext?: unknown;
}

function sortedSet(values: readonly string[] | null | undefined) {
  return [...new Set(values ?? [])].sort();
}

function stableValue(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(stableValue).sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)));
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, entry]) => [key, stableValue(entry)]),
    );
  }
  return value ?? null;
}

/**
 * 为有效权限、模块状态、数据范围及预览上下文生成稳定键。
 * A stable key for the effective grants, module state, data scope, and preview context.
 */
export function effectiveAuthorizationSignature(user: AuthorizationSignatureInput | null | undefined) {
  if (!user) return JSON.stringify({ userId: null });
  return JSON.stringify({
    userId: user.userId ?? null,
    authorities: sortedSet(user.authorities),
    globalAuthorities: sortedSet(user.globalAuthorities),
    enabledModules: sortedSet(user.enabledModules),
    authorizationRevision: user.authorizationRevision ?? null,
    authorizationContext: stableValue(user.authorizationContext),
  });
}

export function runtimeNavigationQueryKey(
  user: AuthorizationSignatureInput | null | undefined,
  sessionRevision: number,
) {
  return [
    "navigation-runtime",
    user?.userId ?? null,
    sessionRevision,
    effectiveAuthorizationSignature(user),
  ] as const;
}
