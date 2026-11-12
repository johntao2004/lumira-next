import type { FrontendModule, FrontendPageContext } from "@/platform/modules/types";
import { authenticationConsoleManifest } from "./manifest";
import { ProCard } from "@ant-design/pro-components";
import { lazy, type ReactNode } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";
import { routeTabsForGroup } from "@/platform/modules/route-tabs";
import "./authentication-security-layout.css";

const AuthenticationPage = lazy(() => import("./authentication-page").then(({ AuthenticationPage }) => ({ default: AuthenticationPage })));
const CaptchaPage = lazy(() => import("./captcha-page").then(({ CaptchaPage }) => ({ default: CaptchaPage })));
const PasskeyPage = lazy(() => import("./passkey-page").then(({ PasskeyPage }) => ({ default: PasskeyPage })));
const RegistrationPage = lazy(() => import("./registration-page").then(({ RegistrationPage }) => ({ default: RegistrationPage })));
const SecurityPage = lazy(() => import("./security-page").then(({ SecurityPage }) => ({ default: SecurityPage })));
const WechatPage = lazy(() => import("./wechat-page").then(({ WechatPage }) => ({ default: WechatPage })));
function SettingsCard({ children }: { children: ReactNode }) {
  return <ProCard className="platform-settings-card">{children}</ProCard>;
}

function AuthenticationSecurityTabs(context: FrontendPageContext) {
  const {hasRouteAccess,hasRouteOperation}=context;
  return <RouteTabs preserveContent tabs={routeTabsForGroup(authenticationConsoleManifest, "authentication-security", {
    security: hasRouteAccess("security") ? <SecurityPage canWrite={hasRouteOperation("security", "update")} /> : undefined,
    authentication: hasRouteAccess("authentication") ? <SettingsCard><AuthenticationPage
      canWrite={hasRouteOperation("authentication", "update")}
      pageContext={context}
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

function AuthenticationSecurityView(context: FrontendPageContext) {
  return <div className="authentication-security-workspace">
    <AuthenticationSecurityTabs {...context} />
  </div>;
}

export const authenticationConsoleFrontendModule = {
  ...authenticationConsoleManifest,
  pages: {
    security: context => <AuthenticationSecurityView {...context} />,
    authentication: context => <AuthenticationSecurityView {...context} />,
    passkey: context => <AuthenticationSecurityView {...context} />,
    wechat: context => <AuthenticationSecurityView {...context} />,
    captcha: context => <AuthenticationSecurityView {...context} />,
    registration: context => <AuthenticationSecurityView {...context} />,
  },
} satisfies FrontendModule;
