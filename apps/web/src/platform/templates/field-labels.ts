import { localizeProfileFieldLabel } from "../profile-fields/localization.ts";

type FieldLike = { key: string; label: string; source: string };
type Translate = (key: string, params?: Record<string, string | number>) => string;

export function userFieldLabel(field: FieldLike, t: Translate, context?: "import") {
  switch (field.key) {
    case "username": return t("username");
    case "displayName": return t("name");
    case "enabled": return t("status");
    case "roles": return context === "import" ? t("userImportRolesCodes") : t("userFieldRoles");
    case "organizations": return context === "import" ? t("userImportOrganizationsCodes") : t("userFieldOrganizations");
    default: return localizeProfileFieldLabel({ ...field, custom: field.source === "custom" }, t);
  }
}

export function userFieldSourceLabel(source: string, t: Translate) {
  switch (source) {
    case "account": return t("userFieldSourceAccount");
    case "roles": return t("userFieldRoles");
    case "organizations": return t("userFieldOrganizations");
    case "profile": return t("userFieldSourceProfile");
    case "custom": return t("userFieldSourceCustom");
    default: return source;
  }
}
