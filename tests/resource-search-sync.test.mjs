import test from "node:test";
import assert from "node:assert/strict";
import { resourceSearchSnapshot } from "../apps/web/src/platform/components/resource-search-sync.ts";

test("URL override changes produce a new form signature without changing request filters", () => {
  const request = { code: null, name: null, status: null };
  const first = resourceSearchSnapshot(request, { code: "A", name: "Alpha", status: "ACTIVE" });
  const next = resourceSearchSnapshot(request, { code: "B", name: "Beta", status: "DISABLED" });
  assert.notEqual(first.key, next.key);
  assert.equal(next.values.code, "B");
  assert.equal(resourceSearchSnapshot(request, { code: "A", name: "Alpha", status: "ACTIVE" }).key, first.key);
});

test("search snapshots preserve date objects and ignore equivalent object allocation/order", () => {
  const date = new Date("2026-10-19T00:00:00Z");
  const dayjsLike = { toISOString: () => date.toISOString() };
  const first = resourceSearchSnapshot({ range: [dayjsLike, date], code: "A" });
  const next = resourceSearchSnapshot({ code: "A", range: [dayjsLike, date] });
  assert.equal(first.key, next.key);
  assert.equal(first.values.range[0], dayjsLike);
  assert.equal(first.values.range[1], date);
  assert.notEqual(resourceSearchSnapshot({ range: [date.toISOString()] }).key, resourceSearchSnapshot({ range: [date] }).key);
});

test("invalid date values remain form values rather than crashing synchronization", () => {
  const invalid = new Date("invalid");
  const snapshot = resourceSearchSnapshot({ date: invalid });
  assert.equal(snapshot.values.date, invalid);
  assert.match(snapshot.key, /invalid/);
});
