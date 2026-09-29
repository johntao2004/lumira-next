import type { FrontendModule } from "@/platform/modules/types";
import { localizationManifest } from "./manifest";
import { lazy } from "react";

const LocalizationPage = lazy(() => import("./localization-page").then(({ LocalizationPage }) => ({ default: LocalizationPage })));

export const localizationFrontendModule = {
  ...localizationManifest,
  pages: {
    localization: ({ hasGlobal }) => (
      <LocalizationPage canWrite={hasGlobal("platform:localization:write")} canImport={hasGlobal("platform:localization:import")} />
    ),
  },
} satisfies FrontendModule;
