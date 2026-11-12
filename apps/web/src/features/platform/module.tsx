import type { FrontendModule } from "@/platform/modules/types";
import { platformManifest } from "./manifest";
import { lazy } from "react";

// Keep the platform manifest cheap to load.  A route imports its page only
// when the user opens it; the module registry still owns route/page
// validation, so code splitting does not weaken the module contract.
const ModulesPage = lazy(() => import("@/features/modules/modules-page").then(({ ModulesPage }) => ({ default: ModulesPage })));

export const platformFrontendModule = {
  ...platformManifest,
  pages: {
    modules: ({ hasOperation }) => <ModulesPage
      canInstall={hasOperation("install")}
      canUpdate={hasOperation("update")}
      canUninstall={hasOperation("uninstall")}
    />,

  },
} satisfies FrontendModule;
