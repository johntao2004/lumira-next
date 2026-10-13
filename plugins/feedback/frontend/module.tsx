import type { FrontendModule } from "@/platform/modules/types";
import { feedbackManifest } from "./manifest";
import { lazy } from "react";

const FeedbackPage = lazy(() => import("./feedback-page").then(({ FeedbackPage }) => ({ default: FeedbackPage })));

export const feedbackFrontendModule = {
  ...feedbackManifest,
  pages: {
    feedback: ({ hasOperation, readOnlyPreview }) => (
      <FeedbackPage
        canRead={hasOperation("read")}
        canCreate={!readOnlyPreview && hasOperation("create")}
        canUpdateStatus={!readOnlyPreview && hasOperation("update")}
        canReply={!readOnlyPreview && hasOperation("reply")}
      />
    ),
  },
} satisfies FrontendModule;
