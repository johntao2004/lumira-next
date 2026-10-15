export type FileQuotaSettings = { maximumBytes: number; maximumFiles: number };
export type CapacityUnit = "bytes" | "gib";

export const DEFAULT_CAPACITY_UNIT: CapacityUnit = "bytes";

const GIB = 1_073_741_824n;
const MAX_SAFE_QUOTA = BigInt(Number.MAX_SAFE_INTEGER);
const DECIMAL_SCALE = 10n ** 30n;
const GIB_DECIMAL_FACTOR = 5n ** 30n;

export function nonNegativeInteger(value: unknown): number | null {
  if (typeof value === "number") return Number.isSafeInteger(value) && value >= 0 ? value : null;
  if (typeof value !== "string" || !/^\d+$/.test(value)) return null;
  const parsed = BigInt(value);
  return parsed <= MAX_SAFE_QUOTA ? Number(parsed) : null;
}

export function capacityBytes(value: unknown, unit: CapacityUnit): number | null {
  if (unit === "bytes") return nonNegativeInteger(value);
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value) || value < 0) return null;
    value = String(value);
  }
  if (typeof value !== "string") return null;
  const match = /^(\d+)(?:\.(\d+))?$/.exec(value);
  if (!match) return null;
  const fraction = match[2] ?? "";
  if (fraction.length > 30) return null;
  const denominator = 10n ** BigInt(fraction.length);
  const numerator = BigInt(match[1]) * denominator + BigInt(fraction || "0");
  const scaledBytes = numerator * GIB;
  if (scaledBytes % denominator !== 0n) return null;
  const bytes = scaledBytes / denominator;
  return bytes <= MAX_SAFE_QUOTA ? Number(bytes) : null;
}

export function bytesToGib(bytes: number): string {
  if (!Number.isSafeInteger(bytes) || bytes < 0) throw new RangeError("invalidFileQuota");
  const scaled = BigInt(bytes) * GIB_DECIMAL_FACTOR;
  const whole = scaled / DECIMAL_SCALE;
  const fraction = (scaled % DECIMAL_SCALE).toString().padStart(30, "0").replace(/0+$/, "");
  return fraction ? `${whole}.${fraction}` : whole.toString();
}

export function convertCapacityValue(value: unknown, fromUnit: CapacityUnit, toUnit: CapacityUnit): string | null {
  const bytes = capacityBytes(value, fromUnit);
  if (bytes === null) return null;
  return toUnit === "bytes" ? String(bytes) : bytesToGib(bytes);
}

export function normalizeFileQuotaSettings(values: Record<string, unknown>): FileQuotaSettings | null {
  const unit: CapacityUnit = values.maximumBytesUnit === "gib" ? "gib" : "bytes";
  const maximumBytes = capacityBytes(values.maximumBytes, unit);
  const maximumFiles = nonNegativeInteger(values.maximumFiles);
  return maximumBytes === null || maximumFiles === null ? null : { maximumBytes, maximumFiles };
}
