import type { FrontendModule } from "@/platform/modules/types";
import { notificationsManifest } from "./manifest";
import { lazy } from "react";

const AdminNotificationsPage = lazy(() => import("./admin-notifications-page").then(({ AdminNotificationsPage }) => ({ default: AdminNotificationsPage })));

export const notificationsFrontendModule = {
  ...notificationsManifest,
  pages: {
    notifications: ({ hasOperation, hasGlobal }) => <AdminNotificationsPage
      canCreate={hasOperation("create")}
      canTraceEvents={hasGlobal("platform:event-outbox:access")}
      canTraceOperations={hasGlobal("platform:audit:access")}
    />,
  },
} satisfies FrontendModule;
