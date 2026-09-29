import type { FrontendModule } from "@/platform/modules/types";
import { sensitiveWordsManifest } from "./manifest";
import { lazy } from "react";

const SensitiveWordsPage = lazy(() => import("./sensitive-words-page").then(({ SensitiveWordsPage }) => ({ default: SensitiveWordsPage })));

export const sensitiveWordsFrontendModule = {
  ...sensitiveWordsManifest,
  pages: {
    "sensitive-words": ({ hasGlobal }) => (
      <SensitiveWordsPage canWrite={hasGlobal("platform:sensitive-words:write")} canImport={hasGlobal("platform:sensitive-words:import")} />
    ),
  },
} satisfies FrontendModule;
