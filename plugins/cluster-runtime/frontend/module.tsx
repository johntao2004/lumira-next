import type { FrontendModule } from "@/platform/modules/types";
import { clusterRuntimeManifest } from "./manifest";
export const clusterRuntimeFrontendModule = { ...clusterRuntimeManifest, pages: {} } satisfies FrontendModule;
