import { lazy } from "react";
import type { FrontendModule } from "@/platform/modules/types";
import { mfaManifest } from "./manifest";

const AuthenticatorPage = lazy(() => import("./authenticator-page").then(module => ({ default: module.AuthenticatorPage })));
const ManagementPage = lazy(() => import("./management-page").then(module => ({ default: module.ManagementPage })));

export const mfaFrontendModule = {
  ...mfaManifest,
  pages: {
    "mfa-authenticator": () => <AuthenticatorPage />,
    "mfa-management": ({ hasOperation, readOnlyPreview }) => (
      <ManagementPage canReset={!readOnlyPreview && hasOperation("reset")} />
    ),
  },
} satisfies FrontendModule;
