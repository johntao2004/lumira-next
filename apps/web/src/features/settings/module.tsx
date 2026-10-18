import type { FrontendModule } from "@/platform/modules/types";
import { settingsManifest } from "./manifest";
import { ProCard } from "@ant-design/pro-components";
import { lazy, type ReactNode } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";
import { routeTabsForGroup } from "@/platform/modules/route-tabs";
import "./authentication-security-layout.css";

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

function FileRetentionLegacyRoute({ hasRouteOperation }: { hasRouteAccess: (routeId: string) => boolean; hasRouteOperation: (routeId: string, actionId: string) => boolean }) {
  return <FileRetentionPage canWrite={hasRouteOperation("file-retention", "update")} />;
}

function SettingsCard({ children }: { children: ReactNode }) {
  return <ProCard className="platform-settings-card">{children}</ProCard>;
}

type RouteAccessCheck = (routeId: string) => boolean;
type RouteOperationCheck = (routeId: string, actionId: string) => boolean;

function PersonalizationTabs({ hasRouteAccess, hasRouteOperation }: { hasRouteAccess: RouteAccessCheck; hasRouteOperation: RouteOperationCheck }) {
  return <RouteTabs preserveContent tabs={routeTabsForGroup(settingsManifest, "personalization", {
    branding: hasRouteAccess("branding") ? <BrandingPage canWrite={hasRouteOperation("branding", "update")} /> : undefined,
    watermark: hasRouteAccess("watermark") ? <WatermarkPage canWrite={hasRouteOperation("watermark", "update")} /> : undefined,
    agreements: hasRouteAccess("agreements") ? <AgreementsPage canWrite={hasRouteOperation("agreements", "update")} /> : undefined,
    maintenance: hasRouteAccess("maintenance") ? <MaintenancePage canWrite={hasRouteOperation("maintenance", "update")} /> : undefined,
  })} />;
}

function MessageChannelTabs({ hasRouteAccess, hasRouteOperation }: { hasRouteAccess: RouteAccessCheck; hasRouteOperation: RouteOperationCheck }) {
  return <RouteTabs preserveContent tabs={routeTabsForGroup(settingsManifest, "message-channels", {
    smtp: hasRouteAccess("smtp") ? <SmtpPage canWrite={hasRouteOperation("smtp", "update")} /> : undefined,
    sms: hasRouteAccess("sms") ? <SmsPage canWrite={hasRouteOperation("sms", "update")} /> : undefined,
  })} />;
}

function AuthenticationSecurityTabs({ hasRouteAccess, hasRouteOperation }: { hasRouteAccess: RouteAccessCheck; hasRouteOperation: RouteOperationCheck }) {
  return <RouteTabs preserveContent tabs={routeTabsForGroup(settingsManifest, "authentication-security", {
    security: hasRouteAccess("security") ? <SecurityPage canWrite={hasRouteOperation("security", "update")} /> : undefined,
    authentication: hasRouteAccess("authentication") ? <SettingsCard><AuthenticationPage
      canWrite={hasRouteOperation("authentication", "update")}
      hasRouteAccess={hasRouteAccess}
      hasRouteOperation={hasRouteOperation}
    /></SettingsCard> : undefined,
    passkey: hasRouteAccess("passkey") ? <PasskeyPage canWrite={hasRouteOperation("passkey", "update")} /> : undefined,
    wechat: hasRouteAccess("wechat") ? <SettingsCard><WechatPage canWrite={hasRouteOperation("wechat", "update")} /></SettingsCard> : undefined,
    captcha: hasRouteAccess("captcha") ? <SettingsCard><CaptchaPage canWrite={hasRouteOperation("captcha", "update")} /></SettingsCard> : undefined,
    registration: hasRouteAccess("registration") ? <SettingsCard><RegistrationPage
      canWrite={hasRouteOperation("registration", "update")}
      canReadRoles={hasRouteOperation("registration", "readRoles") || hasRouteOperation("registration", "assignDefaultRole")}
      canAssignDefaultRole={hasRouteOperation("registration", "assignDefaultRole")}
    /></SettingsCard> : undefined,
  })} />;
}

function AuthenticationSecurityView({ hasRouteAccess, hasRouteOperation }: { hasRouteAccess: RouteAccessCheck; hasRouteOperation: RouteOperationCheck }) {
  return <div className="authentication-security-workspace">
    <AuthenticationSecurityTabs hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />
  </div>;
}

export const settingsFrontendModule = {
  ...settingsManifest,
  pages: {
    "profile-fields": ({ hasRouteOperation }) => <ProfileFieldsPage
      canWrite={hasRouteOperation("profile-fields", "update")}
      canReadDictionaries={hasRouteOperation("profile-fields", "readDictionaries")}
    />,
    maintenance: ({ hasRouteAccess, hasRouteOperation }) => <PersonalizationTabs hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    security: ({ hasRouteAccess, hasRouteOperation }) => <AuthenticationSecurityView hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    authentication: ({ hasRouteAccess, hasRouteOperation }) => <AuthenticationSecurityView hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    passkey: ({ hasRouteAccess, hasRouteOperation }) => <AuthenticationSecurityView hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    wechat: ({ hasRouteAccess, hasRouteOperation }) => <AuthenticationSecurityView hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    captcha: ({ hasRouteAccess, hasRouteOperation }) => <AuthenticationSecurityView hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    registration: ({ hasRouteAccess, hasRouteOperation }) => <AuthenticationSecurityView hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    watermark: ({ hasRouteAccess, hasRouteOperation }) => <PersonalizationTabs hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    agreements: ({ hasRouteAccess, hasRouteOperation }) => <PersonalizationTabs hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    smtp: ({ hasRouteAccess, hasRouteOperation }) => <MessageChannelTabs hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    sms: ({ hasRouteAccess, hasRouteOperation }) => <MessageChannelTabs hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    branding: ({ hasRouteAccess, hasRouteOperation }) => <PersonalizationTabs hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
    "file-retention": ({ hasRouteAccess, hasRouteOperation }) => <FileRetentionLegacyRoute hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} />,
  },
} satisfies FrontendModule;
