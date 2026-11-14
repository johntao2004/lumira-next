import type { FrontendModule } from "@/platform/modules/types";
import { localizationManifest } from "./manifest";
import { lazy } from "react";

const LocalizationPage = lazy(() => import("./localization-page").then(({ LocalizationPage }) => ({ default: LocalizationPage })));

export const localizationFrontendModule = {
  ...localizationManifest,
  pages: {
    localization: ({ hasOperation, hasRouteAccess }) => (
      <LocalizationPage
        canRead={hasRouteAccess("localization") || hasOperation("read")}
        canCreate={hasOperation("create")}
        canUpdate={hasOperation("update")}
        canDelete={hasOperation("delete")}
        canImport={hasOperation("import")}
        canPublish={hasOperation("publish")}
      />
    ),
  },
} satisfies FrontendModule;
