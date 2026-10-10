import type { FrontendModule } from "@/platform/modules/types";
import { diffuseAvatarManifest } from "./manifest";

export const diffuseAvatarFrontendModule = {
  ...diffuseAvatarManifest,
  pages: {},
} satisfies FrontendModule;
