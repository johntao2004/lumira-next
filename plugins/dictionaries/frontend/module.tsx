import type { FrontendModule } from "@/platform/modules/types";
import { dictionariesManifest } from "./manifest";
import { lazy } from "react";

const DictionariesPage = lazy(() => import("./dictionaries-page").then(({ DictionariesPage }) => ({ default: DictionariesPage })));

export const dictionariesFrontendModule = {
  ...dictionariesManifest,
  pages: {
    dictionaries: ({ hasOperation }) => <DictionariesPage
      canCreate={hasOperation("create")}
      canUpdate={hasOperation("update")}
      canDelete={hasOperation("delete")}
      canImport={hasOperation("import")}
      canExport={hasOperation("export")}
    />,
  },
} satisfies FrontendModule;
