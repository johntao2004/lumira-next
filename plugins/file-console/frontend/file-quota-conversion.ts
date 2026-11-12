export type FileQuotaSettings = { maximumBytes: number; maximumFiles: number };
export type CapacityUnit = "mb" | "gb";

export const DEFAULT_CAPACITY_UNIT: CapacityUnit = "gb";
export const DEFAULT_FINITE_CAPACITY_BYTES = 1_000_000_000;
export const DEFAULT_FINITE_FILE_COUNT = 1_000;

const CAPACITY_BYTES: Record<CapacityUnit, bigint> = {
  mb: 1_000_000n,
  gb: 1_000_000_000n,
};
const MAX_SAFE_QUOTA = BigInt(Number.MAX_SAFE_INTEGER);

export function nonNegativeInteger(value: unknown): number | null {
  if (typeof value === "number") return Number.isSafeInteger(value) && value >= 0 ? value : null;
  if (typeof value !== "string" || !/^\d+$/.test(value)) return null;
  const parsed = BigInt(value);
  return parsed <= MAX_SAFE_QUOTA ? Number(parsed) : null;
}

export function capacityBytes(value: unknown, unit: CapacityUnit): number | null {
  if (typeof value === "number") {
    if (!Number.isFinite(value) || value < 0) return null;
    value = String(value);
  }
  if (typeof value !== "string") return null;
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value);
  if (!match) return null;

  const fraction = (match[2] ?? "").padEnd(2, "0");
  const hundredths = BigInt(match[1]) * 100n + BigInt(fraction || "0");
  const bytes = (hundredths * CAPACITY_BYTES[unit]) / 100n;
  return bytes <= MAX_SAFE_QUOTA ? Number(bytes) : null;
}

export function formatCapacity(bytes: unknown, unit: CapacityUnit): string {
  const safeBytes = nonNegativeInteger(bytes);
  if (safeBytes === null) return "";

  const factor = CAPACITY_BYTES[unit];
  const roundedHundredths = (BigInt(safeBytes) * 100n + factor / 2n) / factor;
  const whole = roundedHundredths / 100n;
  const fraction = (roundedHundredths % 100n).toString().padStart(2, "0");
  return `${whole}.${fraction}`;
}

export function selectCapacityUnit(bytes: unknown): CapacityUnit {
  const safeBytes = nonNegativeInteger(bytes);
  return safeBytes !== null && safeBytes > 0 && safeBytes < Number(CAPACITY_BYTES.gb) ? "mb" : "gb";
}

export function normalizeFileQuotaSettings(values: Record<string, unknown>): FileQuotaSettings | null {
  const maximumBytes = nonNegativeInteger(values.maximumBytes);
  const maximumFiles = nonNegativeInteger(values.maximumFiles);
  return maximumBytes === null || maximumFiles === null ? null : { maximumBytes, maximumFiles };
}

export function maximumSafeCapacity(unit: CapacityUnit): string {
  const factor = CAPACITY_BYTES[unit];
  const hundredths = (MAX_SAFE_QUOTA * 100n) / factor;
  return `${hundredths / 100n}.${(hundredths % 100n).toString().padStart(2, "0")}`;
}
