import type { FrontendModule } from "@/platform/modules/types";
import { lazy } from "react";
import { fileConsoleManifest } from "./manifest";
const AdminFilesPage = lazy(() => import("./admin-files-page").then(({ AdminFilesPage }) => ({ default: AdminFilesPage })));
export const fileConsoleFrontendModule = {
  ...fileConsoleManifest,
  pages: { "files-all": context => <AdminFilesPage
    pageContext={context}
    canDelete={context.hasOperation("delete")}
    canRestore={context.hasOperation("restore")}
    canPurge={context.hasOperation("purge")}
    canPreview={context.hasOperation("preview")}
    canDownload={context.hasOperation("download")}
    canReadRetention={context.hasRouteAccess("file-retention")}
    canReadQuota={context.hasRouteAccess("files-all")}
    canUpdateQuota={context.hasOperation("update-quota")}
  /> },
} satisfies FrontendModule;
