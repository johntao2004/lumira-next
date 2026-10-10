"use client";
import {BrandingProvider} from "./branding/provider";
import type {Branding} from "./branding/model";
import { history } from "@umijs/max";
import { safeReturnPath } from "./auth/return-path";
import { App, ConfigProvider, theme } from "antd";
import { ProConfigProvider } from "@ant-design/pro-components";
import { StyleProvider } from "antd-style";
import enUS from "antd/locale/en_US";
import {
  loadLocaleMessages,
  resolveLocale,
  translate,
  type Locale,
  type MessageKey,
} from "./i18n/messages";
async function loadAntLocale(locale: Locale) {
  switch (locale) {
    case "zh-CN": return (await import("antd/locale/zh_CN")).default;
    case "zh-TW": return (await import("antd/locale/zh_TW")).default;
    case "ja": return (await import("antd/locale/ja_JP")).default;
    case "ko": return (await import("antd/locale/ko_KR")).default;
    case "fr": return (await import("antd/locale/fr_FR")).default;
    case "es": return (await import("antd/locale/es_ES")).default;
    case "de": return (await import("antd/locale/de_DE")).default;
    case "en": return enUS;
  }
}
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, useRef } from "react";
import { canonicalLocale, defaultPreferences, PREFERENCES_COOKIE, parsePreferences, serializePreferences, type PreferencesValue } from "./preferences/model";
import { api, mutate } from "./api/client";
import type { CurrentUser } from "./auth/api-types";
import { createAccountLocaleSync } from "./preferences/account-locale-sync";
import { AppErrorBoundary } from "./shell/app-error-boundary";
import { useDisplayScale } from "./display/use-display-scale";
import { clearAuthorizationContext } from "./api/client";
import { currentSessionUserId, invalidateSession, SESSION_STORAGE_KEY, subscribeSessionBoundary } from "./auth/session-lifecycle";
const Preferences = createContext({
  ...defaultPreferences,
  updatePreferences: (_patch: Partial<PreferencesValue>) => {},
  dark: false,
  locale: "zh-CN",
  t: (key: string, params?: Record<string, string | number>) =>
    translate("zh-CN", key as MessageKey, params),
  toggleDark: () => {},
  setLocale: async (_locale: string) => {},
});
export const usePreferences = () => useContext(Preferences);
export function Providers({ children, initialBranding, initialPreferences, hasSavedPreferences }: { initialBranding:Branding; children: React.ReactNode; initialPreferences: PreferencesValue; hasSavedPreferences: boolean }) {
  const [preferences, setPreferences] = useState(initialPreferences);
  const displayScale = useDisplayScale();
  const [staticLocaleRevision, setStaticLocaleRevision] = useState(0);
  const [antLocale, setAntLocale] = useState(enUS);
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
  const [accountLocale] = useState(() => createAccountLocaleSync({
    load: (signal) => api<{ locale: string | null }>("/account/locale", { signal }),
    save: (value, signal) => mutate<{ locale: string | null }>("/account/locale", "PUT", { locale: value }, signal),
    apply: (value) => updatePreferences({ locale: value }),
  }));
  useEffect(() => {
    const syncAccount = () => {
      const user = client.getQueryData<CurrentUser>(["session"]);
      void accountLocale.setAccount(user?.userId ?? null);
    };
    const unsubscribe = client.getQueryCache().subscribe(syncAccount);
    syncAccount();
    return () => { unsubscribe(); accountLocale.dispose(); };
  }, [client, accountLocale]);
  useEffect(() => {
    const pendingRevalidations = new Set<number>();
    const unsubscribe = subscribeSessionBoundary(({ kind, userId }) => {
      if (kind === "invalidated") {
        client.clear();
        clearAuthorizationContext();
      } else {
        // Keep the in-flight /auth/me query that established the new identity;
        // every account-scoped query is removed and must be loaded again.
        client.removeQueries({ predicate: query => query.queryKey[0] !== "session" });
        clearAuthorizationContext();

        // Another bootstrap caller can establish the new identity first, which
        // makes a same-epoch duplicate /auth/me fail its epoch guard. If the
        // session query has not already cached the accepted user, revalidate it
        // after the establishing promise can settle instead of leaving the
        // workspace in the transient error state until its next poll.
        const timer = window.setTimeout(() => {
          pendingRevalidations.delete(timer);
          if (!userId || currentSessionUserId() !== userId) return;
          const cached = client.getQueryData<CurrentUser>(["session"]);
          if (cached?.userId === userId) return;
          window.dispatchEvent(new Event("lumira:session-revalidate"));
        }, 0);
        pendingRevalidations.add(timer);
      }
    });
    return () => {
      unsubscribe();
      pendingRevalidations.forEach(timer => window.clearTimeout(timer));
    };
  }, [client]);
  useEffect(() => {
    const reauthenticate = (event: StorageEvent) => {
      if (event.key !== SESSION_STORAGE_KEY || !event.newValue) return;
      invalidateSession(false);
      clearAuthorizationContext();
      window.dispatchEvent(new Event("lumira:session-revalidate"));
    };
    window.addEventListener("storage", reauthenticate);
    return () => window.removeEventListener("storage", reauthenticate);
  }, []);
  useEffect(() => {
    const refresh = () => setRuntimeRevision((value) => value + 1);
    window.addEventListener("lumira:localization-changed", refresh);
    return () => window.removeEventListener("lumira:localization-changed", refresh);
  }, []);
  useEffect(() => {
    const expire = () => {
      if (!window.location.pathname.startsWith("/dashboard/")) return;
      const returnTo = safeReturnPath(window.location.pathname + window.location.search + window.location.hash);
      invalidateSession(true);
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
    api<Array<{ locale?: unknown }>>("/public/localization/locales", { signal: controller.signal })
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
    let active = true;
    const selectedLocale = resolveLocale(locale);
    void Promise.all([loadLocaleMessages(selectedLocale), loadAntLocale(selectedLocale)])
      .then(([, loadedAntLocale]) => {
        if (!active) return;
        setAntLocale(loadedAntLocale);
        setStaticLocaleRevision((revision) => revision + 1);
      })
      .catch(() => {
        // English remains a complete static fallback when a locale chunk fails.
        if (active) setAntLocale(enUS);
      });
    return () => { active = false; };
  }, [locale]);
  useEffect(() => {
    const controller = new AbortController();
    setRuntimeMessages({});
    api<{ messages?: unknown }>(`/public/localization/bundle/${encodeURIComponent(locale)}`, { signal: controller.signal })
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
  const setLocale = useCallback((next: string) => accountLocale.select(next), [accountLocale]);
  const t = useCallback((key: string, params?: Record<string, string | number>) => {
    const template = runtimeMessages[key] ?? translate(resolveLocale(locale), key as MessageKey, params);
    return runtimeMessages[key]
      ? template.replace(/\{(\w+)\}/g, (placeholder, name: string) => String(params?.[name] ?? placeholder))
      : template;
  }, [locale, runtimeMessages, staticLocaleRevision]);
  const preferencesValue = useMemo(() => ({
    ...preferences, updatePreferences, dark, locale, toggleDark, setLocale, t,
  }), [preferences, updatePreferences, dark, locale, toggleDark, setLocale, t]);
  return (
    <Preferences.Provider value={preferencesValue}>
      <QueryClientProvider client={client}>
        <ConfigProvider
          locale={antLocale}
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
                  <AppErrorBoundary locale={locale} t={t}>
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
