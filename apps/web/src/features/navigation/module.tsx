import type { FrontendModule } from "@/platform/modules/types";
import { navigationManifest } from "./manifest";
import { lazy } from "react";

const NavigationPage = lazy(() => import("./navigation-page").then(({ NavigationPage }) => ({ default: NavigationPage })));

export const navigationFrontendModule = {
  ...navigationManifest,
  pages: {
    navigation: ({ hasGlobal }) => <NavigationPage canWrite={hasGlobal("platform:navigation:write")} />,
  },
} satisfies FrontendModule;
