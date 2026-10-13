import type { QueryClient } from "@tanstack/react-query";

function sameQueryKey(left: readonly unknown[], right: readonly unknown[]) {
  return left.length === right.length && left.every((part, index) => Object.is(part, right[index]));
}

function isAuthorizationIndependentQuery(query: { queryKey: readonly unknown[] }) {
  const root = query.queryKey[0];
  if (typeof root !== "string") return false;

  // Public configuration is shared across users and deliberately uses a
  // `public-*` cache-key namespace. These queries must survive an account
  // authorization transition so global providers never lose required data.
  return root.startsWith("public-") || root === "auth-methods" || root === "password-policy";
}

/**
 * Retire protected read caches when the effective authorization changes.
 * Cancellation prevents a late response from restoring data from the old
 * scope; explicitly clearing state also removes rows while a new request is
 * pending or fails. Mutations are left alone so an authorized write can settle.
 */
export async function refreshAuthorizationScopedQueries(
  client: QueryClient,
  currentNavigationKey: readonly unknown[],
) {
  const isProtected = (query: { queryKey: readonly unknown[] }) =>
    query.queryKey[0] !== "session" &&
    !sameQueryKey(query.queryKey, currentNavigationKey) &&
    !isAuthorizationIndependentQuery(query);
  const filters = { predicate: isProtected };

  await client.cancelQueries(filters, { revert: true });

  for (const query of client.getQueryCache().findAll(filters)) {
    query.setState({
      data: undefined,
      dataUpdatedAt: 0,
      error: null,
      errorUpdatedAt: 0,
      fetchFailureCount: 0,
      fetchFailureReason: null,
      isInvalidated: true,
      status: "pending",
      fetchStatus: "idle",
    });
  }

  await client.refetchQueries({ ...filters, type: "active" }, { cancelRefetch: false });
}
