import { lazy } from "react";
import type { FrontendModule } from "@/platform/modules/types";
import { runtimeObserverManifest } from "./manifest";

const RuntimeObserverPage = lazy(() => import("./runtime-observer-page").then(({ RuntimeObserverPage: page }) => ({ default: page })));

export const runtimeObserverFrontendModule = {
  ...runtimeObserverManifest,
  pages: {
    "runtime-observer": ({ hasGlobal, hasOperation, readOnlyPreview }) => (
      <RuntimeObserverPage
        canRead={hasOperation("read")}
        canReadMonitoring={hasGlobal("platform:monitoring:access")}
        canListModules={hasGlobal("platform:modules:access")}
        canReadEvents={hasGlobal("platform:event-outbox:access")}
        readOnlyPreview={Boolean(readOnlyPreview)}
      />
    ),
  },
} satisfies FrontendModule;
