import type { FrontendModule } from "@/platform/modules/types";
import { monitoringManifest } from "./manifest";
import { lazy } from "react";

const MonitoringPage = lazy(() => import("./monitoring-page").then(({ MonitoringPage }) => ({ default: MonitoringPage })));
const ApiDocsPage = lazy(() => import("./api-docs-page").then(({ ApiDocsPage }) => ({ default: ApiDocsPage })));

export const monitoringFrontendModule = {
  ...monitoringManifest,
  pages: {
    monitoring: ({ hasGlobal }) => <MonitoringPage canTraceEvents={hasGlobal("platform:event-outbox:access")} />,
    "api-docs": () => <ApiDocsPage />,
  },
} satisfies FrontendModule;
