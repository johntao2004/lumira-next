import type { FrontendModule } from "@/platform/modules/types";
import { identityManifest } from "./manifest";
import { history } from "@umijs/max";
import { lazy, useEffect } from "react";

const AccountSecurityPage = lazy(() => import("./account-security-page").then(({ AccountSecurityPage }) => ({ default: AccountSecurityPage })));
const AccountRolePreviewPage = lazy(() => import("./role-preview-page").then(({ AccountRolePreviewPage }) => ({ default: AccountRolePreviewPage })));
const AccountWechatPage = lazy(() => import("./wechat-account-page").then(({ AccountWechatPage }) => ({ default: AccountWechatPage })));
const AccountProfilePage = lazy(() => import("./profile-drawer").then(({ ProfileDrawer }) => ({
  default: () => <ProfileDrawer open embedded onClose={() => {}} />,
})));
const AccountPasswordPage = lazy(() => import("./password-drawer").then(({ PasswordDrawer }) => ({
  default: () => <PasswordDrawer open embedded onClose={() => {}} />,
})));
const AccountSessionsPage = lazy(() => import("./sessions-drawer").then(({ SessionsDrawer }) => ({
  default: () => <SessionsDrawer open embedded onClose={() => {}} />,
})));

function AccountProfileLegacyRedirect() {
  useEffect(() => {
    history.replace(`/dashboard/account-security${window.location.search}${window.location.hash}`);
  }, []);
  return null;
}

export const identityFrontendModule = {
  ...identityManifest,
  pages: {
    "account-profile": () => <AccountProfileLegacyRedirect />,
    "account-security": () => <AccountProfilePage />,
    "account-authentication-security": () => <AccountSecurityPage />,
    "account-role-preview": () => <AccountRolePreviewPage />,
    "account-wechat": () => <AccountWechatPage />,
    "account-password": () => <AccountPasswordPage />,
    "account-sessions": () => <AccountSessionsPage />,
  },
} satisfies FrontendModule;
