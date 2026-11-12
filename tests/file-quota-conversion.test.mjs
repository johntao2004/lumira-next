import test from "node:test";
import assert from "node:assert/strict";
import {
  capacityBytes,
  DEFAULT_CAPACITY_UNIT,
  formatCapacity,
  maximumSafeCapacity,
  normalizeFileQuotaSettings,
  selectCapacityUnit,
} from "../plugins/file-console/frontend/file-quota-conversion.ts";

test("uses decimal MB and GB conversions with up to two decimal places", () => {
  assert.equal(capacityBytes("1.25", "gb"), 1_250_000_000);
  assert.equal(capacityBytes("1.25", "mb"), 1_250_000);
  assert.equal(capacityBytes("0.01", "mb"), 10_000);
  assert.equal(capacityBytes("0", "gb"), 0);
  assert.equal(capacityBytes("1.001", "mb"), null);
});

test("formats capacities to two decimals using decimal SI units", () => {
  assert.equal(formatCapacity(1_073_741_824, "gb"), "1.07");
  assert.equal(formatCapacity(1_075_000_000, "gb"), "1.08");
  assert.equal(formatCapacity(1_250_000, "mb"), "1.25");
  assert.equal(formatCapacity(10_000, "mb"), "0.01");
  assert.equal(formatCapacity(1_000_000_000, "gb"), "1.00");
  assert.equal(formatCapacity(0, "gb"), "0.00");
  assert.equal(formatCapacity(null, "gb"), "");
});

test("defaults to GB and selects MB only for nonzero capacities below one GB", () => {
  assert.equal(DEFAULT_CAPACITY_UNIT, "gb");
  assert.equal(selectCapacityUnit(0), "gb");
  assert.equal(selectCapacityUnit(999_999_999), "mb");
  assert.equal(selectCapacityUnit(1_000_000_000), "gb");
  assert.equal(selectCapacityUnit(1_073_741_824), "gb");
});

test("rounding the display never changes untouched raw bytes on submission", () => {
  const exactBytes = 1_073_741_824;
  assert.equal(formatCapacity(exactBytes, "gb"), "1.07");
  assert.equal(formatCapacity(exactBytes, "mb"), "1073.74");

  const saved = normalizeFileQuotaSettings({ maximumBytes: exactBytes, maximumFiles: "4" });
  assert.deepEqual(saved, { maximumBytes: exactBytes, maximumFiles: 4 });
  assert.equal(normalizeFileQuotaSettings({ maximumBytes: "", maximumFiles: "4" }), null);
});

test("preserves zero as the unlimited value for both quota dimensions", () => {
  assert.deepEqual(normalizeFileQuotaSettings({ maximumBytes: "0", maximumFiles: "0" }), {
    maximumBytes: 0,
    maximumFiles: 0,
  });
  assert.equal(normalizeFileQuotaSettings({ maximumBytes: "", maximumFiles: "0" }), null);
  assert.equal(normalizeFileQuotaSettings({ maximumBytes: "0", maximumFiles: "" }), null);
});

test("rejects empty, malformed, unsafe, and non-integer quota values", () => {
  for (const invalid of [null, "", "NaN", "Infinity", "-1", "1.001", Number.NaN]) {
    assert.equal(capacityBytes(invalid, "mb"), null, String(invalid));
  }
  assert.equal(capacityBytes("9007199.25", "gb"), 9_007_199_250_000_000);
  assert.equal(capacityBytes("9007199.26", "gb"), null);
  assert.equal(capacityBytes(maximumSafeCapacity("mb"), "mb"), 9_007_199_254_740_000);
  assert.equal(normalizeFileQuotaSettings({ maximumBytes: 1, maximumFiles: "2.5" }), null);
  assert.equal(normalizeFileQuotaSettings({ maximumBytes: Number.MAX_SAFE_INTEGER + 1, maximumFiles: 2 }), null);
});
