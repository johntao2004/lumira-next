import type { FrontendModule } from "@/platform/modules/types";
import { filesManifest } from "./manifest";
export const filesFrontendModule = { ...filesManifest, pages: {} } satisfies FrontendModule;
