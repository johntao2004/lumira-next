import type { FrontendModule } from "@/platform/modules/types";
import { settingsManifest } from "./manifest";
import { lazy } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";

const AgreementsPage = lazy(() => import("./agreements-page").then(({ AgreementsPage }) => ({ default: AgreementsPage })));
const BrandingPage = lazy(() => import("./branding-page").then(({ BrandingPage }) => ({ default: BrandingPage })));
const MaintenancePage = lazy(() => import("./maintenance-page").then(({ MaintenancePage }) => ({ default: MaintenancePage })));
const PasskeyPage = lazy(() => import("./passkey-page").then(({ PasskeyPage }) => ({ default: PasskeyPage })));
const ProfileFieldsPage = lazy(() => import("./profile-fields-page").then(({ ProfileFieldsPage }) => ({ default: ProfileFieldsPage })));
const SecurityPage = lazy(() => import("./security-page").then(({ SecurityPage }) => ({ default: SecurityPage })));
const SmsPage = lazy(() => import("./sms-page").then(({ SmsPage }) => ({ default: SmsPage })));
const SmtpPage = lazy(() => import("./smtp-page").then(({ SmtpPage }) => ({ default: SmtpPage })));
const WatermarkPage = lazy(() => import("./watermark-page").then(({ WatermarkPage }) => ({ default: WatermarkPage })));
const FileRetentionPage = lazy(() => import("./file-retention-page").then(({ FileRetentionPage }) => ({ default: FileRetentionPage })));

function PersonalizationTabs({ canWrite }: { canWrite: boolean }) {
  return <RouteTabs preserveContent tabs={[
    { key: "branding", path: "/dashboard/branding", label: "branding", children: <BrandingPage canWrite={canWrite} /> },
    { key: "watermark", path: "/dashboard/watermark", label: "watermark", children: <WatermarkPage canWrite={canWrite} /> },
    { key: "agreements", path: "/dashboard/agreements", label: "agreements", children: <AgreementsPage canWrite={canWrite} /> },
    { key: "maintenance", path: "/dashboard/maintenance", label: "maintenance", children: <MaintenancePage canWrite={canWrite} /> },
    { key: "file-retention", path: "/dashboard/file-retention", label: "fileRetention", children: <FileRetentionPage canWrite={canWrite} /> },
  ]} />;
}

function MessageChannelTabs({ canWrite }: { canWrite: boolean }) {
  return <RouteTabs preserveContent tabs={[
    { key: "smtp", path: "/dashboard/smtp", label: "smtpSettings", children: <SmtpPage canWrite={canWrite} /> },
    { key: "sms", path: "/dashboard/sms", label: "smsSettings", children: <SmsPage canWrite={canWrite} /> },
  ]} />;
}

function AuthenticationSecurityTabs({ canWrite }: { canWrite: boolean }) {
  return <RouteTabs preserveContent tabs={[
    { key: "security", path: "/dashboard/security", label: "securitySettings", children: <SecurityPage canWrite={canWrite} /> },
    { key: "passkey", path: "/dashboard/passkey", label: "passkeySettings", children: <PasskeyPage canWrite={canWrite} /> },
  ]} />;
}

export const settingsFrontendModule = {
  ...settingsManifest,
  pages: {
    "profile-fields": ({ hasGlobal }) => <ProfileFieldsPage
      canWrite={hasGlobal("platform:settings:write")}
      canReadDictionaries={hasGlobal("platform:dictionaries:read")}
    />,
    maintenance: ({ hasGlobal }) => <PersonalizationTabs canWrite={hasGlobal("platform:settings:write")} />,
    security: ({ hasGlobal }) => <AuthenticationSecurityTabs canWrite={hasGlobal("platform:settings:write")} />,
    passkey: ({ hasGlobal }) => <AuthenticationSecurityTabs canWrite={hasGlobal("platform:settings:write")} />,
    watermark: ({ hasGlobal }) => <PersonalizationTabs canWrite={hasGlobal("platform:settings:write")} />,
    agreements: ({ hasGlobal }) => <PersonalizationTabs canWrite={hasGlobal("platform:settings:write")} />,
    smtp: ({ hasGlobal }) => <MessageChannelTabs canWrite={hasGlobal("platform:settings:write")} />,
    sms: ({ hasGlobal }) => <MessageChannelTabs canWrite={hasGlobal("platform:settings:write")} />,
    branding: ({ hasGlobal }) => <PersonalizationTabs canWrite={hasGlobal("platform:settings:write")} />,
    "file-retention": ({ hasGlobal }) => <PersonalizationTabs canWrite={hasGlobal("platform:settings:write")} />,
  },
} satisfies FrontendModule;
