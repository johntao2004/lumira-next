import type { FrontendModule } from "@/platform/modules/types";
import { lazy } from "react";
import { personalFilesManifest } from "./manifest";
const FilesPage = lazy(() => import("./files-page").then(({ FilesPage }) => ({ default: FilesPage })));
const FileUsage = lazy(() => import("./file-usage").then(({ FileUsage }) => ({ default: FileUsage })));
export const personalFilesFrontendModule = {
  ...personalFilesManifest,
  pages: { files: context => context.embedded?.presentation === "summary" ? <FileUsage /> : <FilesPage /> },
} satisfies FrontendModule;
