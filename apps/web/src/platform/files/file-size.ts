/** Display persisted byte counts as MiB-based MB values; storage and quota logic stay byte-exact. */
export function formatFileSizeMb(sizeBytes: number | null, locale: string): string {
  if (sizeBytes === null) return "—";
  const value = sizeBytes / (1024 * 1024);
  const formatter = new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  if (sizeBytes > 0 && value < 0.01) return `<${formatter.format(0.01)} MB`;
  return `${formatter.format(value)} MB`;
}
