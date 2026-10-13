const formatterCache = new Map<string, Intl.DateTimeFormat>();

function formatter(locale: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const key = `${locale}|${Object.entries(options)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([name, value]) => `${name}=${String(value)}`)
    .join(",")}`;
  const cached = formatterCache.get(key);
  if (cached) return cached;
  const created = new Intl.DateTimeFormat(locale, options);
  // Supported locales and presets are intentionally small. Keep the cache
  // bounded anyway because locale can be supplied by a user preference.
  if (formatterCache.size >= 32) {
    const oldest = formatterCache.keys().next().value;
    if (oldest !== undefined) formatterCache.delete(oldest);
  }
  formatterCache.set(key, created);
  return created;
}

/** Shared, invalid-date-safe formatting for every feature module. */
export function formatDateTime(
  value: string | number | Date | null | undefined,
  locale: string,
  options: Intl.DateTimeFormatOptions = { dateStyle: "medium", timeStyle: "short" },
): string {
  if (value === null || value === undefined || value === "") return "—";
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.valueOf()) ? "—" : formatter(locale, options).format(date);
}

/** Format quota values with the shared binary units and existing display precision. */
export function formatQuotaBytes(bytes: number, locale: string): string {
  const unit = bytes >= 1024 ** 3 ? 3 : bytes >= 1024 ** 2 ? 2 : bytes >= 1024 ? 1 : 0;
  const formatted = new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(bytes / 1024 ** unit);
  return `${formatted} ${["B", "KiB", "MiB", "GiB"][unit]}`;
}

/** Format role/effective storage limits, where 0 is unlimited and null inherits. */
export function formatQuotaLimitBytes(
  bytes: number | null,
  locale: string,
  labels: { unlimited: string; inherited: string },
): string {
  if (bytes === null) return labels.inherited;
  if (bytes === 0) return labels.unlimited;
  return formatQuotaBytes(bytes, locale);
}
