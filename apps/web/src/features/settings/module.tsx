import type { FrontendModule } from "@/platform/modules/types";
import { settingsManifest } from "./manifest";
import { ProCard } from "@ant-design/pro-components";
import { lazy, useEffect, type ReactNode } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";
import { routeTabsForGroup } from "@/platform/modules/route-tabs";

const AgreementsPage = lazy(() => import("./agreements-page").then(({ AgreementsPage }) => ({ default: AgreementsPage })));
const AuthenticationPage = lazy(() => import("./authentication-page").then(({ AuthenticationPage }) => ({ default: AuthenticationPage })));
const CaptchaPage = lazy(() => import("./captcha-page").then(({ CaptchaPage }) => ({ default: CaptchaPage })));
const BrandingPage = lazy(() => import("./branding-page").then(({ BrandingPage }) => ({ default: BrandingPage })));
const MaintenancePage = lazy(() => import("./maintenance-page").then(({ MaintenancePage }) => ({ default: MaintenancePage })));
const PasskeyPage = lazy(() => import("./passkey-page").then(({ PasskeyPage }) => ({ default: PasskeyPage })));
const RegistrationPage = lazy(() => import("./registration-page").then(({ RegistrationPage }) => ({ default: RegistrationPage })));
const ProfileFieldsPage = lazy(() => import("./profile-fields-page").then(({ ProfileFieldsPage }) => ({ default: ProfileFieldsPage })));
const SecurityPage = lazy(() => import("./security-page").then(({ SecurityPage }) => ({ default: SecurityPage })));
const SmsPage = lazy(() => import("./sms-page").then(({ SmsPage }) => ({ default: SmsPage })));
const SmtpPage = lazy(() => import("./smtp-page").then(({ SmtpPage }) => ({ default: SmtpPage })));
const WatermarkPage = lazy(() => import("./watermark-page").then(({ WatermarkPage }) => ({ default: WatermarkPage })));
const WechatPage = lazy(() => import("./wechat-page").then(({ WechatPage }) => ({ default: WechatPage })));
const FileRetentionPage = lazy(() => import("./file-retention-page").then(({ FileRetentionPage }) => ({ default: FileRetentionPage })));

function FileRetentionLegacyRedirect() {
  useEffect(() => {
    window.location.replace("/dashboard/files-all#tab=file-retention");
  }, []);
  return null;
}

function FileRetentionLegacyRoute({ hasGlobal }: { hasGlobal: (permission: string) => boolean }) {
  if (hasGlobal("platform:files:read")) return <FileRetentionLegacyRedirect />;
  return <FileRetentionPage canWrite={hasGlobal("platform:settings:write")} />;
}

function SettingsCard({ children }: { children: ReactNode }) {
  return <ProCard className="platform-settings-card">{children}</ProCard>;
}

function PersonalizationTabs({ canWrite }: { canWrite: boolean }) {
  return <RouteTabs preserveContent tabs={routeTabsForGroup(settingsManifest, "personalization", {
    branding: <BrandingPage canWrite={canWrite} />,
    watermark: <WatermarkPage canWrite={canWrite} />,
    agreements: <AgreementsPage canWrite={canWrite} />,
    maintenance: <MaintenancePage canWrite={canWrite} />,
  })} />;
}

function MessageChannelTabs({ canWrite }: { canWrite: boolean }) {
  return <RouteTabs preserveContent tabs={routeTabsForGroup(settingsManifest, "message-channels", {
    smtp: <SmtpPage canWrite={canWrite} />,
    sms: <SmsPage canWrite={canWrite} />,
  })} />;
}

function AuthenticationSecurityTabs({ canWrite, canWriteRoles, canReadRoles }: { canWrite: boolean; canWriteRoles: boolean; canReadRoles: boolean }) {
  return <RouteTabs preserveContent tabs={routeTabsForGroup(settingsManifest, "authentication-security", {
    security: <SecurityPage canWrite={canWrite} />,
    authentication: <SettingsCard><AuthenticationPage canWrite={canWrite} /></SettingsCard>,
    passkey: <PasskeyPage canWrite={canWrite} />,
    wechat: <SettingsCard><WechatPage canWrite={canWrite} /></SettingsCard>,
    captcha: <SettingsCard><CaptchaPage canWrite={canWrite} /></SettingsCard>,
    registration: <SettingsCard><RegistrationPage canWrite={canWrite && canWriteRoles} canReadRoles={canReadRoles} /></SettingsCard>,
  })} />;
}

function AuthenticationSecurityView({ hasGlobal }: { hasGlobal: (authority: string) => boolean }) {
  return <AuthenticationSecurityTabs
    canWrite={hasGlobal("platform:settings:write")}
    canWriteRoles={hasGlobal("platform:roles:write")}
    canReadRoles={hasGlobal("platform:roles:read")}
  />;
}

export const settingsFrontendModule = {
  ...settingsManifest,
  pages: {
    "profile-fields": ({ hasGlobal }) => <ProfileFieldsPage
      canWrite={hasGlobal("platform:settings:write")}
      canReadDictionaries={hasGlobal("platform:dictionaries:read")}
    />,
    maintenance: ({ hasGlobal }) => <PersonalizationTabs canWrite={hasGlobal("platform:settings:write")} />,
    security: ({ hasGlobal }) => <AuthenticationSecurityView hasGlobal={hasGlobal} />,
    authentication: ({ hasGlobal }) => <AuthenticationSecurityView hasGlobal={hasGlobal} />,
    passkey: ({ hasGlobal }) => <AuthenticationSecurityView hasGlobal={hasGlobal} />,
    wechat: ({ hasGlobal }) => <AuthenticationSecurityView hasGlobal={hasGlobal} />,
    captcha: ({ hasGlobal }) => <AuthenticationSecurityView hasGlobal={hasGlobal} />,
    registration: ({ hasGlobal }) => <AuthenticationSecurityView hasGlobal={hasGlobal} />,
    watermark: ({ hasGlobal }) => <PersonalizationTabs canWrite={hasGlobal("platform:settings:write")} />,
    agreements: ({ hasGlobal }) => <PersonalizationTabs canWrite={hasGlobal("platform:settings:write")} />,
    smtp: ({ hasGlobal }) => <MessageChannelTabs canWrite={hasGlobal("platform:settings:write")} />,
    sms: ({ hasGlobal }) => <MessageChannelTabs canWrite={hasGlobal("platform:settings:write")} />,
    branding: ({ hasGlobal }) => <PersonalizationTabs canWrite={hasGlobal("platform:settings:write")} />,
    "file-retention": ({ hasGlobal }) => <FileRetentionLegacyRoute hasGlobal={hasGlobal} />,
  },
} satisfies FrontendModule;
