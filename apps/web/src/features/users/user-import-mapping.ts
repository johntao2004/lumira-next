import type { ImportColumn, ImportField } from "@/platform/templates/types";

export function normalizeImportHeader(value: string) {
  return value.trim().replace(/^\uFEFF/, "").toLowerCase().replace(/[\s_\-（）()]/g, "");
}

function sameHeader(header: string, candidate: string) {
  if (header === candidate) return true;
  const left = normalizeImportHeader(header);
  const right = normalizeImportHeader(candidate);
  return left.length > 0 && left === right;
}

/** Match a column to a field key, the API label, or the label shown in the current locale. */
export function automaticImportMapping(
  columns: ImportColumn[],
  fields: ImportField[],
  visibleLabel: (field: ImportField) => string,
) {
  const used = new Set<string>();
  const mapping: Record<string, string> = {};
  for (const column of columns) {
    const available = fields.filter((item) => !used.has(item.key));
    const field = available.find((item) => column.name === item.key)
      ?? available.find((item) => sameHeader(column.name, item.key))
      ?? available.find((item) => sameHeader(column.name, item.label) || sameHeader(column.name, visibleLabel(item)));
    if (!field) continue;
    mapping[column.name] = field.key;
    used.add(field.key);
  }
  return mapping;
}
