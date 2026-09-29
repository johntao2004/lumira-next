export const PREFERENCES_COOKIE = "lumira-next.preferences";
export interface PreferencesValue {
  dark: boolean;
  locale: string;
}
export const defaultPreferences: PreferencesValue = {
  dark: false,
  locale: "zh-CN",
};
/** Syntax validation only; the public locale registry validates registration at runtime. */
export function canonicalLocale(value: unknown): string | null {
  if (typeof value !== "string" || value.length > 63 || !/^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/u.test(value)) return null;
  try { return Intl.getCanonicalLocales(value)[0] ?? null; }
  catch { return null; }
}
export function parsePreferences(raw?: string | null): PreferencesValue {
  try {
    const value = JSON.parse(decodeURIComponent(raw ?? ""));
    if (!value || typeof value !== "object" || Array.isArray(value)) return { ...defaultPreferences };
    const locale = canonicalLocale(value.locale);
    return {
      dark: value.dark === true,
      locale: locale ?? defaultPreferences.locale,
    };
  } catch { return { ...defaultPreferences }; }
}
export function serializePreferences(value: PreferencesValue): string {
  return encodeURIComponent(JSON.stringify(value));
}
