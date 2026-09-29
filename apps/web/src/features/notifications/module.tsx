import type { FrontendModule } from "@/platform/modules/types";
import { notificationsManifest } from "./manifest";
import { lazy } from "react";

const AdminNotificationsPage = lazy(() => import("./admin-notifications-page").then(({ AdminNotificationsPage }) => ({ default: AdminNotificationsPage })));

export const notificationsFrontendModule = {
  ...notificationsManifest,
  pages: {
    notifications: ({ hasGlobal }) => <AdminNotificationsPage canWrite={hasGlobal("platform:notifications:write")} />,
  },
} satisfies FrontendModule;
