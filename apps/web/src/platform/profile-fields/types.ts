export type ProfileFieldType = "TEXT" | "TEXTAREA" | "NUMBER" | "DATE" | "SELECT";

export type ProfilePlacement = {
  page: "account-profile" | "admin-user-editor" | "public-registration";
  tab: "basic" | "details" | "custom" | "profile" | "additional";
  sortNo: number;
};

export type ProfileFieldOption = { value: string; label: string };

/** The safe common subset consumed by the shared field renderer. */
export type ProfileFieldDefinition = {
  key: string;
  label: string;
  custom?: boolean;
  description?: string | null;
  type: ProfileFieldType;
  required: boolean;
  placeholder?: string | null;
  options?: readonly (string | ProfileFieldOption)[];
  groupKey?: string;
  groupLabel?: string;
  dictionaryCode?: string | null;
  placements?: readonly ProfilePlacement[];
};

export type DictionarySelection = {
  itemId: string;
  label: string;
  enabled: boolean;
};

export type ProfileFieldRun =
  | { kind: "field"; field: ProfileFieldDefinition }
  | { kind: "group"; key: string; label: string; fields: readonly ProfileFieldDefinition[] };

/** Build contiguous groups without changing placement sort order. */
export function profileFieldRuns(fields: readonly ProfileFieldDefinition[]): ProfileFieldRun[] {
  const runs: ProfileFieldRun[] = [];
  let index = 0;
  while (index < fields.length) {
    const field = fields[index];
    if (!field.groupKey || !field.groupLabel) {
      runs.push({ kind: "field", field });
      index += 1;
      continue;
    }
    const grouped = [field];
    index += 1;
    while (index < fields.length
      && fields[index].groupKey === field.groupKey
      && fields[index].groupLabel === field.groupLabel) {
      grouped.push(fields[index]);
      index += 1;
    }
    runs.push({ kind: "group", key: field.groupKey, label: field.groupLabel, fields: grouped });
  }
  return runs;
}
