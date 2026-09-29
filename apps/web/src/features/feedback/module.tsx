import type { FrontendModule } from "@/platform/modules/types";
import { feedbackManifest } from "./manifest";
import { lazy } from "react";

const FeedbackPage = lazy(() => import("./feedback-page").then(({ FeedbackPage }) => ({ default: FeedbackPage })));

export const feedbackFrontendModule = {
  ...feedbackManifest,
  pages: {
    feedback: ({ hasGlobal, authorities }) => (
      <FeedbackPage canCreate={authorities.includes("platform:feedback:create")} canManage={hasGlobal("platform:feedback:manage")} />
    ),
  },
} satisfies FrontendModule;
