import type { FrontendModule } from "@/platform/modules/types";
import { filesManifest } from "./manifest";
import { lazy } from "react";

const AdminFilesPage = lazy(() => import("./admin-files-page").then(({ AdminFilesPage }) => ({ default: AdminFilesPage })));
const FilesPage = lazy(() => import("./files-page").then(({ FilesPage }) => ({ default: FilesPage })));

export const filesFrontendModule = {
  ...filesManifest,
  pages: {
    files: () => <FilesPage />,
    "files-all": ({ hasGlobal }) => <AdminFilesPage canWrite={hasGlobal("platform:files:write")} />,
  },
} satisfies FrontendModule;
