import type { FrontendModule } from "@/platform/modules/types";
import { identityManifest } from "./manifest";
import { lazy } from "react";

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

export const identityFrontendModule = {
  ...identityManifest,
  pages: {
    "account-profile": () => <AccountProfilePage />,
    "account-security": () => <AccountSecurityPage />,
    "account-role-preview": () => <AccountRolePreviewPage />,
    "account-wechat": () => <AccountWechatPage />,
    "account-password": () => <AccountPasswordPage />,
    "account-sessions": () => <AccountSessionsPage />,
  },
} satisfies FrontendModule;
