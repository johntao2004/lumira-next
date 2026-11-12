import type { FrontendModule } from "@/platform/modules/types";
import { lazy } from "react";
import { fileRetentionManifest } from "./manifest";
const FileRetentionPage = lazy(() => import("./file-retention-page").then(({ FileRetentionPage }) => ({ default: FileRetentionPage })));
export const fileRetentionFrontendModule = {
  ...fileRetentionManifest,
  pages: { "file-retention": ({ hasOperation }) => <FileRetentionPage canWrite={hasOperation("update")} /> },
} satisfies FrontendModule;
