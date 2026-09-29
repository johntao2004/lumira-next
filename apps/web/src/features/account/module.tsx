import type { FrontendModule } from "@/platform/modules/types";
import { identityManifest } from "./manifest";

export const identityFrontendModule = {
  ...identityManifest,
  pages: {},
} satisfies FrontendModule;
