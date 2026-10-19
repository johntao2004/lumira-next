import type { FrontendModule } from "@/platform/modules/types";
import { sensitiveWordsManifest } from "./manifest";
import { lazy } from "react";

const SensitiveWordsPage = lazy(() => import("./sensitive-policies-page").then(({ SensitiveWordsPage }) => ({ default: SensitiveWordsPage })));

export const sensitiveWordsFrontendModule = {
  ...sensitiveWordsManifest,
  pages: {
    "sensitive-words": ({ hasOperation, hasRouteAccess }) => (
      <SensitiveWordsPage
        canRead={hasRouteAccess("sensitive-words") || hasOperation("read")}
        canCreate={hasOperation("create")}
        canUpdate={hasOperation("update")}
        canDelete={hasOperation("delete")}
        canImport={hasOperation("import")}
        canPolicyUpdate={hasOperation("policy-update")}
      />
    ),
  },
} satisfies FrontendModule;
