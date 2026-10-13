import type { MessageKey } from "../i18n/messages";

type TranslateProfileFieldMessage = (key: MessageKey) => string;

export type LocalizableProfileField = {
  key: string;
  label: string;
  custom?: boolean;
};

export type LocalizableProfileFieldGroup = {
  groupKey: string;
  groupLabel: string;
  custom?: boolean;
};

const builtInDefaultLabels = {
  displayName: { defaultLabel: "Display name", messageKey: "name" },
  realName: { defaultLabel: "Real name", messageKey: "realName" },
  birthDate: { defaultLabel: "Birth date", messageKey: "birthMonth" },
  region: { defaultLabel: "Region", messageKey: "region" },
  avatarUrl: { defaultLabel: "Avatar", messageKey: "avatar" },
} satisfies Record<string, { defaultLabel: string; messageKey: MessageKey }>;

export function localizeProfileFieldLabel(
  field: LocalizableProfileField,
  t: TranslateProfileFieldMessage,
): string {
  if (field.custom === true) return field.label;
  const definition = builtInDefaultLabels[field.key as keyof typeof builtInDefaultLabels];
  return definition && field.label === definition.defaultLabel ? t(definition.messageKey) : field.label;
}

export function localizeProfileFieldGroupLabel(
  group: LocalizableProfileFieldGroup,
  t: TranslateProfileFieldMessage,
): string {
  if (group.custom === true) return group.groupLabel;
  return group.groupKey === "basic" && group.groupLabel === "Basic information"
    ? t("userBasicInformation")
    : group.groupLabel;
}

/** Adapt an editor's stored text to its localized input value without changing the form value. */
export function localizeProfileFieldEditorValue(
  field: LocalizableProfileField & LocalizableProfileFieldGroup,
  property: "label" | "groupLabel",
  value: unknown,
  t: TranslateProfileFieldMessage,
): string {
  const text = typeof value === "string" ? value : "";
  return property === "label"
    ? localizeProfileFieldLabel({ key: field.key, label: text, custom: field.custom }, t)
    : localizeProfileFieldGroupLabel({ groupKey: field.groupKey, groupLabel: text, custom: field.custom }, t);
}
