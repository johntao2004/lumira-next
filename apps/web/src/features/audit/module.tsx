import type { FrontendModule } from "@/platform/modules/types";
import { auditManifest } from "./manifest";
import { lazy } from "react";

const AuditPage = lazy(() => import("./audit-page").then(({ AuditPage }) => ({ default: AuditPage })));

export const auditFrontendModule = {
  ...auditManifest,
  pages: { audit: ({ hasGlobal }) => <AuditPage canTraceEvents={hasGlobal("platform:event-outbox:access")} /> },
} satisfies FrontendModule;
