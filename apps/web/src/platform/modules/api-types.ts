export interface ModuleInfo {
  /** Added after the required flag; optional so clients can read older backend responses. */
  core?: boolean;
  enabled: boolean;
  required: boolean;
  stateVersion: number;
  id: string;
  name: string;
  version: string;
  dependencies: string[];
}
