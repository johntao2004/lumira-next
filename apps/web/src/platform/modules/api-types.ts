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

export interface ModulePackage {
  id: string;
  moduleId: string;
  name: string;
  packageVersion: string;
  schemaVersion: string;
  apiVersion: string;
  artifactDigest: string;
  manifestDigest: string;
  artifactRef: string;
  dependencies: string[];
  permissions: string[];
  routes: string[];
  status: "STAGED" | "ACTIVE" | "SUPERSEDED" | "REJECTED";
  revision: number;
  installedBy: string;
  installReason: string;
  createdAt: string;
  updatedAt: string;
  activatedAt: string | null;
  supersededAt: string | null;
  runtimeReady: boolean;
}
