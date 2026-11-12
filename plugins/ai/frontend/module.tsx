import type { FrontendModule } from "@/platform/modules/types";
import { lazy } from "react";
import { aiManifest } from "./manifest";
const AiConsolePage = lazy(() => import("./ai-console-page").then(({ AiConsolePage }) => ({ default: AiConsolePage })));
const AiModelsPage = lazy(() => import("./ai-models-page").then(({ AiModelsPage }) => ({ default: AiModelsPage })));
export const aiFrontendModule = {
  ...aiManifest,
  pages: {
    "ai-console": ({ hasOperation }) => <AiConsolePage
      canReadUsers={hasOperation("readUsers")}
      canCreateUsers={hasOperation("createUser")}
      canUpdateUsers={hasOperation("updateUser")}
      canDeleteUsers={hasOperation("deleteUser")}
    />,
    "ai-models": ({ hasOperation }) => <AiModelsPage canCreate={hasOperation("create")} canUpdate={hasOperation("update")} canDelete={hasOperation("delete")} canTest={hasOperation("test")} />,
  },
} satisfies FrontendModule;
