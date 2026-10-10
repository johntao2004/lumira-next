import type { FrontendModule } from "@/platform/modules/types";
import { platformManifest } from "./manifest";
import { lazy } from "react";

// Keep the platform manifest cheap to load.  A route imports its page only
// when the user opens it; the module registry still owns route/page
// validation, so code splitting does not weaken the module contract.
const ModulesPage = lazy(() => import("@/features/modules/modules-page").then(({ ModulesPage }) => ({ default: ModulesPage })));
const AiConsolePage = lazy(() => import("@/features/ai/ai-console-page").then(({ AiConsolePage }) => ({ default: AiConsolePage })));
const AiModelsPage = lazy(() => import("@/features/ai/ai-models-page").then(({ AiModelsPage }) => ({ default: AiModelsPage })));

export const platformFrontendModule = {
  ...platformManifest,
  pages: {
    modules: ({ hasGlobal }) => <ModulesPage canWrite={hasGlobal("platform:modules:write")} />,
    "ai-console": ({ hasGlobal }) => <AiConsolePage canReadUsers={hasGlobal("platform:users:read")} canWriteUsers={hasGlobal("platform:users:write")} />,
    "ai-models": ({ hasGlobal }) => <AiModelsPage canWrite={hasGlobal("platform:settings:write")} />,
  },
} satisfies FrontendModule;
