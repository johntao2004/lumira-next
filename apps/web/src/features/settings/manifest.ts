import type { FrontendModuleManifest } from "../../platform/modules/types.ts";
/** Required published settings foundation; administration pages are optional plugins. */
export const settingsManifest={id:"settings",version:"1.0.0",required:true,dependencies:["identity","platform"],routes:[]} as const satisfies FrontendModuleManifest;
