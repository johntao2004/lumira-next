"use client";
import { App, Button, Dropdown } from "antd";
import { GlobalOutlined } from "@ant-design/icons";
import { useQuery } from "@tanstack/react-query";
import { usePreferences } from "../providers";
import { api } from "../api/client";
import { languages } from "./messages";
type RegisteredLocale = { locale: string; displayName: string };
export function LanguageSwitcher({ className }: { className?: string } = {}) {
  const { locale, setLocale, t } = usePreferences();
  const { message } = App.useApp();
  const registry = useQuery({
    queryKey: ["public-localization-locales"],
    queryFn: ({ signal }) => api<RegisteredLocale[]>("/public/localization/locales", { signal }),
    staleTime: 60_000,
  });
  const options = registry.data?.length
    ? registry.data.map(({ locale: value, displayName }) => ({ value, label: displayName || value }))
    : languages;
  const visibleOptions = options.some((item) => item.value === locale)
    ? options
    : [...options, { value: locale, label: locale }];
  return (
    <Dropdown
      trigger={["click"]}
      menu={{
        selectable: true,
        selectedKeys: [locale],
        items: visibleOptions.map(({ value, label }) => ({
          key: value,
          label: <span lang={value}>{label}</span>,
        })),
        onClick: ({ key }) => {
          const selected = visibleOptions.find((item) => item.value === key);
          if (selected) void setLocale(selected.value).catch(() => message.error(t("accountLocaleSaveFailed")));
        },
      }}
    >
      <Button className={className} type="text" aria-label={t("language")} icon={<GlobalOutlined />}>
        <span className="language-switcher-label">{visibleOptions.find((item) => item.value === locale)?.label}</span>
      </Button>
    </Dropdown>
  );
}
