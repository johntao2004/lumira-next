import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { getMonitoringRefetchInterval, REALTIME_POLL_MS } from '../apps/web/src/features/monitoring/monitoring-refresh-interval.ts';

test('monitoring polling backs off for current failures and returns to baseline after success resets the count', () => {
  let fetchFailureCount = 0;
  assert.equal(getMonitoringRefetchInterval('realtime', 'idle', fetchFailureCount), REALTIME_POLL_MS);
  fetchFailureCount = 1;
  assert.equal(getMonitoringRefetchInterval('realtime', 'idle', fetchFailureCount), REALTIME_POLL_MS * 2);
  fetchFailureCount = 2;
  assert.equal(getMonitoringRefetchInterval('realtime', 'idle', fetchFailureCount), REALTIME_POLL_MS * 4);
  fetchFailureCount = 0; // TanStack resets this counter after a successful fetch.
  assert.equal(getMonitoringRefetchInterval('realtime', 'idle', fetchFailureCount), REALTIME_POLL_MS);
  assert.equal(getMonitoringRefetchInterval(5_000, 'idle', 4), 60_000);
});

test('monitoring polling is disabled in manual mode and while a request is in flight', () => {
  assert.equal(getMonitoringRefetchInterval('manual', 'idle', 0), false);
  assert.equal(getMonitoringRefetchInterval('realtime', 'fetching', 0), false);
});

test('monitoring query passes TanStack’s success-reset failure counter to the polling policy', () => {
  const pageSource = readFileSync(new URL('../apps/web/src/features/monitoring/monitoring-page.tsx', import.meta.url), 'utf8');
  assert.match(pageSource, /currentQuery\.state\.fetchFailureCount/);
  assert.doesNotMatch(pageSource, /currentQuery\.state\.errorUpdateCount/);
});
