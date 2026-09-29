export const REALTIME_POLL_MS = 3_000;
const MAX_FAILURE_BACKOFF_MS = 60_000;

export type MonitoringRefreshMode = "realtime" | "manual" | number;

export function getMonitoringRefetchInterval(
  refreshMode: MonitoringRefreshMode,
  fetchStatus: string,
  fetchFailureCount: number,
): number | false {
  if (refreshMode === "manual" || fetchStatus === "fetching") return false;

  const interval = refreshMode === "realtime" ? REALTIME_POLL_MS : refreshMode;
  const failureMultiplier = 2 ** Math.min(fetchFailureCount, 4);
  return Math.min(interval * failureMultiplier, Math.max(interval, MAX_FAILURE_BACKOFF_MS));
}
