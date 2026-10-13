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

/** A stable key for the effective grants, module state, data scope, and preview context. */
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
