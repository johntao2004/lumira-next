"use client";
import {BrandingProvider} from "./branding/provider";
import type {Branding} from "./branding/model";
import { history } from "@umijs/max";
import { safeReturnPath } from "./auth/return-path";
import { App, ConfigProvider, theme } from "antd";
import { ProConfigProvider } from "@ant-design/pro-components";
import { StyleProvider } from "antd-style";
import zhCN from "antd/locale/zh_CN";
import enUS from "antd/locale/en_US";
import zhTW from "antd/locale/zh_TW";
import jaJP from "antd/locale/ja_JP";
import koKR from "antd/locale/ko_KR";
import frFR from "antd/locale/fr_FR";
import esES from "antd/locale/es_ES";
import deDE from "antd/locale/de_DE";
import {
  resolveLocale,
  translate,
  type Locale,
  type MessageKey,
} from "./i18n/messages";
const antLocales = {
  "zh-CN": zhCN,
  "zh-TW": zhTW,
  en: enUS,
  ja: jaJP,
  ko: koKR,
  fr: frFR,
  es: esES,
  de: deDE,
};
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, useRef } from "react";
import { canonicalLocale, defaultPreferences, PREFERENCES_COOKIE, parsePreferences, serializePreferences, type PreferencesValue } from "./preferences/model";
import { AppErrorBoundary } from "./shell/app-error-boundary";
import { useDisplayScale } from "./display/use-display-scale";
const Preferences = createContext({
  ...defaultPreferences,
  updatePreferences: (_patch: Partial<PreferencesValue>) => {},
  dark: false,
  locale: "zh-CN",
  t: (key: string, params?: Record<string, string | number>) =>
    translate("zh-CN", key as MessageKey, params),
  toggleDark: () => {},
  setLocale: (_locale: string) => {},
});
export const usePreferences = () => useContext(Preferences);
export function Providers({ children, initialBranding, initialPreferences, hasSavedPreferences }: { initialBranding:Branding; children: React.ReactNode; initialPreferences: PreferencesValue; hasSavedPreferences: boolean }) {
  const [preferences, setPreferences] = useState(initialPreferences);
  const displayScale = useDisplayScale();
  const [runtimeMessages, setRuntimeMessages] = useState<Record<string, string>>({});
  const [runtimeRevision, setRuntimeRevision] = useState(0);
  const current = useRef(preferences);
  const { dark, locale } = preferences;
  const updatePreferences = useCallback((patch: Partial<PreferencesValue>) => {
    const next = parsePreferences(serializePreferences({ ...current.current, ...patch }));
    current.current = next;
    setPreferences(next);
    document.cookie = `${PREFERENCES_COOKIE}=${serializePreferences(next)}; Path=/; Max-Age=31536000; SameSite=Lax${location.protocol === "https:" ? "; Secure" : ""}`;
    try { localStorage.setItem(PREFERENCES_COOKIE, serializePreferences(next)); } catch {}
  }, []);
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            refetchOnWindowFocus: false,
            retry: false,
          },
          mutations: { retry: false },
        },
      }),
  );
  useEffect(() => {
    const refresh = () => setRuntimeRevision((value) => value + 1);
    window.addEventListener("lumira:localization-changed", refresh);
    return () => window.removeEventListener("lumira:localization-changed", refresh);
  }, []);
  useEffect(() => {
    const expire = () => {
      if (!window.location.pathname.startsWith("/dashboard/")) return;
      const returnTo = safeReturnPath(window.location.pathname + window.location.search + window.location.hash);
      client.clear();
      history.replace(`/user/login?returnTo=${encodeURIComponent(returnTo)}`);
    };
    window.addEventListener("lumira:session-expired", expire);
    return () => window.removeEventListener("lumira:session-expired", expire);
  }, [client]);
  useEffect(() => {
    // One-time upgrade from legacy local-only preferences; subsequent SSR uses the cookie.
    if (!hasSavedPreferences) {
      try {
        const saved = localStorage.getItem(PREFERENCES_COOKIE);
        if (saved) updatePreferences(parsePreferences(saved));
        else {
          updatePreferences({
            dark: localStorage.getItem("lumira-next.theme") === "dark",
            locale: resolveLocale(localStorage.getItem("lumira-next.locale")),
          });
        }
      } catch {}
    }
    const sync = (event: StorageEvent) => {
      if (event.key === PREFERENCES_COOKIE && event.newValue) {
        const next = parsePreferences(event.newValue);
        current.current = next;
        setPreferences(next);
      }
    };
    window.addEventListener("storage", sync);
    return () => window.removeEventListener("storage", sync);
  }, [hasSavedPreferences]);
  useEffect(() => {
    const controller = new AbortController();
    fetch("/api/public/localization/locales", {
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (response) => response.ok ? response.json() as Promise<Array<{ locale?: unknown }>> : null)
      .then((registered) => {
        if (!registered || !Array.isArray(registered)) return;
        const locales = new Set(registered.flatMap((item) => typeof item?.locale === "string" ? [item.locale] : []));
        const saved = canonicalLocale(current.current.locale);
        if (!saved || !locales.has(saved)) updatePreferences({ locale: defaultPreferences.locale });
      })
      .catch(() => { /* Keep the saved locale while the registry is unreachable. */ });
    return () => controller.abort();
  }, [locale, updatePreferences]);
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    document.documentElement.lang = locale;
  }, [dark, locale]);
  useEffect(() => {
    const controller = new AbortController();
    setRuntimeMessages({});
    fetch(`/api/public/localization/bundle/${encodeURIComponent(locale)}`, {
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (response) => response.ok ? response.json() as Promise<{ messages?: unknown }> : null)
      .then((payload) => {
        if (!payload || !payload.messages || typeof payload.messages !== "object") return;
        const entries = Object.entries(payload.messages as Record<string, unknown>)
          .filter((entry): entry is [string, string] => typeof entry[0] === "string" && typeof entry[1] === "string")
          .slice(0, 20_000);
        setRuntimeMessages(Object.fromEntries(entries));
      })
      .catch(() => { /* Static messages remain the resilient fallback. */ });
    return () => controller.abort();
  }, [locale, runtimeRevision]);
  const toggleDark = useCallback(() => updatePreferences({ dark: !current.current.dark }), [updatePreferences]);
  const setLocale = useCallback((next: string) => updatePreferences({ locale: next }), [updatePreferences]);
  const t = useCallback((key: string, params?: Record<string, string | number>) => {
    const template = runtimeMessages[key] ?? translate(locale as Locale, key as MessageKey, params);
    return runtimeMessages[key]
      ? template.replace(/\{(\w+)\}/g, (placeholder, name: string) => String(params?.[name] ?? placeholder))
      : template;
  }, [locale, runtimeMessages]);
  const preferencesValue = useMemo(() => ({
    ...preferences, updatePreferences, dark, locale, toggleDark, setLocale, t,
  }), [preferences, updatePreferences, dark, locale, toggleDark, setLocale, t]);
  return (
    <Preferences.Provider value={preferencesValue}>
      <QueryClientProvider client={client}>
        <ConfigProvider
          locale={antLocales[locale as keyof typeof antLocales] ?? enUS}
          modal={{ centered: true }}
          theme={{
            cssVar: { key: "lumira" },
            algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm,
            components: {
              Form: {
                itemMarginBottom: displayScale.formItemGap,
                inlineItemMarginBottom: Math.max(12, displayScale.formItemGap - 4),
                verticalLabelPadding: "0 0 4px",
                verticalLabelMargin: 0,
              },
            },
            token: {
              colorPrimary: "#1677ff",
              borderRadius: 6,
              fontSize: displayScale.fontSize,
              controlHeight: displayScale.controlHeight,
              controlHeightSM: displayScale.controlHeightSM,
              controlHeightLG: displayScale.controlHeightLG,
              fontFamily:
                '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif',
            },
          }}
        >
          <StyleProvider prefix="lumira" ssrInline>
            <ProConfigProvider dark={dark}>
              <App>
                <BrandingProvider initial={initialBranding}>
                  <AppErrorBoundary message={t("pageError")} retryLabel={t("retry")}>
                    {children}
                  </AppErrorBoundary>
                </BrandingProvider>
              </App>
            </ProConfigProvider>
          </StyleProvider>
        </ConfigProvider>
      </QueryClientProvider>
    </Preferences.Provider>
  );
}
