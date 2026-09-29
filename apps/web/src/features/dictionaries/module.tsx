import type { FrontendModule } from "@/platform/modules/types";
import { dictionariesManifest } from "./manifest";
import { lazy } from "react";

const DictionariesPage = lazy(() => import("./dictionaries-page").then(({ DictionariesPage }) => ({ default: DictionariesPage })));

export const dictionariesFrontendModule = {
  ...dictionariesManifest,
  pages: {
    dictionaries: ({ hasGlobal }) => <DictionariesPage canWrite={hasGlobal("platform:dictionaries:write")} />,
  },
} satisfies FrontendModule;
