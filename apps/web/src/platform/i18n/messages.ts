import english from "./messages/en.ts";

export const staticLocales = ["zh-CN","zh-TW","en","ja","ko","fr","es","de"] as const;
export type Locale = (typeof staticLocales)[number];
export type MessageKey = keyof typeof english;
export type LocaleMessages = Record<MessageKey, string>;

type LocaleModule = { messages: LocaleMessages };
type LocaleLoader = () => Promise<LocaleModule>;
const localeLoaders: Partial<Record<Locale, LocaleLoader>> = {
  "zh-CN": () => import("./messages/zh-cn.ts"),
  "zh-TW": () => import("./messages/zh-tw.ts"),
  en: async () => ({ messages: english }),
  ja: () => import("./messages/ja.ts"),
  ko: () => import("./messages/ko.ts"),
  fr: () => import("./messages/fr.ts"),
  es: () => import("./messages/es.ts"),
  de: () => import("./messages/de.ts"),
};
const loadedMessages: Partial<Record<Locale, LocaleMessages>> = { en: english };

// Keep the English key registry synchronous; product locales are loaded on demand.
export const messages = { en: english } as const;
export const languages: { value: Locale; label: string }[] = [
  { value: "zh-CN", label: "简体中文" },
  { value: "zh-TW", label: "繁體中文" },
  { value: "en", label: "English" },
  { value: "ja", label: "日本語" },
  { value: "ko", label: "한국어" },
  { value: "fr", label: "Français" },
  { value: "es", label: "Español" },
  { value: "de", label: "Deutsch" },
];

export function resolveLocale(value: string | null | undefined): Locale {
  const normalized = value?.toLowerCase().replaceAll("_", "-") ?? "";
  if (/^zh-(tw|hk|mo|hant)(-|$)/.test(normalized)) return "zh-TW";
  if (normalized === "zh" || normalized.startsWith("zh-")) return "zh-CN";
  const base = normalized.split("-")[0];
  return languages.find((language) => language.value === base)?.value ?? "zh-CN";
}

export async function loadLocaleMessages(locale: Locale): Promise<LocaleMessages> {
  const cached = loadedMessages[locale];
  if (cached) return cached;
  const loader = localeLoaders[locale];
  if (!loader) return english;
  const dictionary = (await loader()).messages;
  loadedMessages[locale] = dictionary;
  return dictionary;
}

export function isMessageKey(value: string): value is MessageKey {
  return Object.hasOwn(english, value);
}

export function translate(
  locale: Locale,
  key: MessageKey,
  params: Record<string, string | number> = {},
) {
  // Runtime-installed locales are not keys in the static source dictionary.
  const template = loadedMessages[locale]?.[key] ?? english[key] ?? key;
  return template.replace(/\{(\w+)\}/g, (placeholder, name: string) => String(params[name] ?? placeholder));
}
