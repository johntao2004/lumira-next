/**
 * 授权变化时取消并清空受保护读缓存后刷新活跃查询，同时保留公开配置、当前会话／导航及仍由服务端裁决的写操作。
 * Cancel and clear protected read caches before refetching active queries after authorization changes, preserving public configuration, current session and navigation queries, and writes still adjudicated by the server.
 */
import type { QueryClient } from "@tanstack/react-query";

function sameQueryKey(left: readonly unknown[], right: readonly unknown[]) {
  return left.length === right.length && left.every((part, index) => Object.is(part, right[index]));
}

function isAuthorizationIndependentQuery(query: { queryKey: readonly unknown[] }) {
  const root = query.queryKey[0];
  if (typeof root !== "string") return false;

  // 公共配置跨用户共享，并有意使用 public-* 缓存键命名空间；账户授权切换时须保留这些查询，避免全局提供器丢失所需数据。
  // Public configuration is shared across users and deliberately uses a
  // `public-*` cache-key namespace. These queries must survive an account
  // authorization transition so global providers never lose required data.
  return root.startsWith("public-") || root === "auth-methods" || root === "password-policy";
}

/**
 * 有效授权变化时淘汰受保护的读取缓存；取消请求防止迟到响应恢复旧范围数据，显式清空状态确保新请求等待或失败时旧行也不可见；写请求保持不变，以便已授权写入完成。
 *
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
