import assert from 'node:assert/strict';
import test from 'node:test';
import { getMonitoringRefetchInterval, REALTIME_POLL_MS } from '../apps/web/src/features/monitoring/monitoring-refresh-interval.ts';

test('monitoring polling backs off for current failures and returns to baseline after success resets the count', () => {
  let consecutiveFailures = 0;
  assert.equal(getMonitoringRefetchInterval('realtime', 'idle', consecutiveFailures), REALTIME_POLL_MS);
  consecutiveFailures = 1;
  assert.equal(getMonitoringRefetchInterval('realtime', 'idle', consecutiveFailures), REALTIME_POLL_MS * 2);
  consecutiveFailures = 2;
  assert.equal(getMonitoringRefetchInterval('realtime', 'idle', consecutiveFailures), REALTIME_POLL_MS * 4);
  consecutiveFailures = 0; // The page resets the cross-request streak on success.
  assert.equal(getMonitoringRefetchInterval('realtime', 'idle', consecutiveFailures), REALTIME_POLL_MS);
  assert.equal(getMonitoringRefetchInterval(5_000, 'idle', 4), 60_000);
});

test('monitoring polling is disabled in manual mode and while a request is in flight', () => {
  assert.equal(getMonitoringRefetchInterval('manual', 'idle', 0), false);
  assert.equal(getMonitoringRefetchInterval('realtime', 'fetching', 0), false);
});

test('long configured intervals are never shortened by the failure backoff cap', () => {
  for (const interval of [60_000, 300_000, 900_000, 3_600_000]) {
    assert.equal(getMonitoringRefetchInterval(interval, 'idle', 0), interval);
    assert.equal(getMonitoringRefetchInterval(interval, 'idle', 1), interval);
    assert.equal(getMonitoringRefetchInterval(interval, 'idle', 20), interval);
  }
});
