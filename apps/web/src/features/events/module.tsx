import type { FrontendModule } from "@/platform/modules/types";
import { eventsManifest } from "./manifest";
import { lazy } from "react";

const OutboxPage = lazy(() => import("./outbox-page").then(({ OutboxPage }) => ({ default: OutboxPage })));

export const eventsFrontendModule = {
  ...eventsManifest,
  pages: {
    "event-outbox": ({ hasGlobal }) => <OutboxPage canReplay={hasGlobal("platform:events:write")} />,
  },
} satisfies FrontendModule;
