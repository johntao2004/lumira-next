import type { FrontendModule } from "@/platform/modules/types";
import { monitoringManifest } from "./manifest";
import { lazy } from "react";

const MonitoringPage = lazy(() => import("./monitoring-page").then(({ MonitoringPage }) => ({ default: MonitoringPage })));

export const monitoringFrontendModule = {
  ...monitoringManifest,
  pages: {
    monitoring: () => <MonitoringPage />,
  },
} satisfies FrontendModule;
