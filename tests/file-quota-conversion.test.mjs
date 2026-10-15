import test from "node:test";
import assert from "node:assert/strict";
import {
  capacityBytes,
  convertCapacityValue,
  DEFAULT_CAPACITY_UNIT,
  normalizeFileQuotaSettings,
} from "../apps/web/src/features/files/file-quota-conversion.ts";

test("GiB conversion preserves one byte and the largest safe byte value exactly", () => {
  const oneByteInGib = "0.000000000931322574615478515625";
  assert.equal(convertCapacityValue(1, "bytes", "gib"), oneByteInGib);
  assert.equal(capacityBytes(oneByteInGib, "gib"), 1);

  const maximumSafeBytes = Number.MAX_SAFE_INTEGER;
  const gibValue = convertCapacityValue(maximumSafeBytes, "bytes", "gib");
  assert.ok(gibValue);
  assert.equal(capacityBytes(gibValue, "gib"), maximumSafeBytes);
  assert.equal(capacityBytes(maximumSafeBytes + 1, "bytes"), null);
});

test("GiB input accepts exact bytes and rejects fractional-byte values", () => {
  assert.equal(capacityBytes("0.5", "gib"), 536_870_912);
  assert.equal(capacityBytes("0.1", "gib"), null);
  assert.equal(capacityBytes("0.000000000931322574615478515625", "gib"), 1);
});

test("saving a GiB draft restores the exact API value and the form's byte default", () => {
  const originalBytes = 8_765_432_109;
  const draft = convertCapacityValue(originalBytes, "bytes", "gib");
  assert.ok(draft);
  const saved = normalizeFileQuotaSettings({ maximumBytes: draft, maximumBytesUnit: "gib", maximumFiles: "0" });
  assert.deepEqual(saved, { maximumBytes: originalBytes, maximumFiles: 0 });
  assert.equal(DEFAULT_CAPACITY_UNIT, "bytes");
  assert.equal(capacityBytes(saved.maximumBytes, DEFAULT_CAPACITY_UNIT), originalBytes);
});

test("quota normalization rejects unsafe capacities and non-integer file counts", () => {
  assert.equal(capacityBytes(1.5, "bytes"), null);
  assert.equal(capacityBytes(Number.MAX_SAFE_INTEGER + 1, "bytes"), null);
  assert.equal(normalizeFileQuotaSettings({ maximumBytes: 1, maximumFiles: "2.5" }), null);
});
