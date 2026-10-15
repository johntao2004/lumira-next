"use client";

import { MoonOutlined, SunOutlined } from "@ant-design/icons";
import { Button, Space } from "antd";
import type { ReactNode } from "react";
import { BrandFooter } from "@/platform/branding/footer";
import { useBranding } from "@/platform/branding/provider";
import { LanguageSwitcher } from "@/platform/i18n/language-switcher";
import { usePreferences } from "@/platform/providers";

export function AuthPageLayout({
  children,
  inert = false,
}: {
  children: ReactNode;
  inert?: boolean;
}) {
  const branding = useBranding();
  const { dark, toggleDark, t } = usePreferences();
  const backgroundUrl = branding.loginBackgroundUrl.trim();

  return <main className="login-page" data-has-background={Boolean(backgroundUrl)} style={{ height: "auto", minHeight: "100dvh", overflowY: "auto" }}>
    {backgroundUrl && <div className="login-hero" aria-hidden="true" style={{ backgroundImage: `url(${JSON.stringify(backgroundUrl)})` }} />}
    <section className="login-shell" inert={inert}>
      <Space className="login-utilities">
        <Button
          type="text"
          aria-label={t(dark ? "lightMode" : "darkMode")}
          icon={dark ? <SunOutlined /> : <MoonOutlined />}
          onClick={toggleDark}
        />
        <LanguageSwitcher />
      </Space>
      <div className="login-content">
        <h1>{t("welcome", { name: branding.websiteName })}</h1>
        {children}
      </div>
      <footer><BrandFooter /></footer>
    </section>
  </main>;
}
