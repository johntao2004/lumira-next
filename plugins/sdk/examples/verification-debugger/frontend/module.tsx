import { lazy } from "react";
import type { FrontendModule } from "@/platform/modules/types";
import { verificationDebuggerManifest } from "./manifest";

const VerificationDebuggerPage = lazy(() => import("./debugger-page").then(({ VerificationDebuggerPage: page }) => ({ default: page })));

export const verificationDebuggerFrontendModule = {
  ...verificationDebuggerManifest,
  pages: {
    "verification-debugger": ({ hasOperation, readOnlyPreview }) => (
      <VerificationDebuggerPage
        canRead={hasOperation("read")}
        canControl={!readOnlyPreview && hasOperation("control")}
        canFixture={!readOnlyPreview && hasOperation("fixture")}
        readOnlyPreview={Boolean(readOnlyPreview)}
      />
    ),
  },
} satisfies FrontendModule;
