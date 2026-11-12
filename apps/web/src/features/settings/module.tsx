import type { FrontendModule } from "@/platform/modules/types";
import {settingsManifest} from "./manifest";
export const settingsFrontendModule={...settingsManifest,pages:{}} satisfies FrontendModule;
