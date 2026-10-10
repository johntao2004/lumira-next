import type { FrontendModule } from "@/platform/modules/types";
import { identityManifest } from "./manifest";
import { lazy } from "react";

const AccountSecurityPage = lazy(() => import("./account-security-page").then(({ AccountSecurityPage }) => ({ default: AccountSecurityPage })));
const AccountRolePreviewPage = lazy(() => import("./role-preview-page").then(({ AccountRolePreviewPage }) => ({ default: AccountRolePreviewPage })));
const AccountWechatPage = lazy(() => import("./wechat-account-page").then(({ AccountWechatPage }) => ({ default: AccountWechatPage })));

export const identityFrontendModule = {
  ...identityManifest,
  pages: {
    "account-security": () => <AccountSecurityPage />,
    "account-role-preview": () => <AccountRolePreviewPage />,
    "account-wechat": () => <AccountWechatPage />,
  },
} satisfies FrontendModule;
