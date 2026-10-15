import type { FrontendModule } from "@/platform/modules/types";
import { filesManifest } from "./manifest";
import { lazy } from "react";

const AdminFilesPage = lazy(() => import("./admin-files-page").then(({ AdminFilesPage }) => ({ default: AdminFilesPage })));
const FilesPage = lazy(() => import("./files-page").then(({ FilesPage }) => ({ default: FilesPage })));

export const filesFrontendModule = {
  ...filesManifest,
  pages: {
    files: () => <FilesPage />,
    "files-all": ({ hasRouteAccess, hasOperation, hasRouteOperation }) => <AdminFilesPage
      canDelete={hasOperation("delete")}
      canRestore={hasOperation("restore")}
      canPurge={hasOperation("purge")}
      canPreview={hasOperation("preview")}
      canDownload={hasOperation("download")}
      canReadRetention={hasRouteAccess("file-retention")}
      canUpdateRetention={hasRouteOperation("file-retention", "update")}
      canReadQuota={hasRouteAccess("files-all")}
      canUpdateQuota={hasRouteOperation("files-all", "update-quota")}
    />,
  },
} satisfies FrontendModule;
