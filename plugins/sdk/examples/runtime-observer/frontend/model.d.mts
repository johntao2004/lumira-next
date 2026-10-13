export type ModuleRecord = {
  id: string;
  name?: string | null;
  version?: string | null;
  dependencies?: string[];
  required?: boolean;
  core?: boolean;
  enabled?: boolean;
  stateVersion?: number;
};

export type PluginRecord = {
  id: string;
  name?: string | null;
  version?: string | null;
  installed?: boolean;
  enabled?: boolean;
  explicitInstall?: boolean;
  digest?: string | null;
  status?: string | null;
  manifest?: { dependencies?: Array<{ id?: string | null; version?: string | null }> } | null;
};

export type MergedModuleRecord = {
  id: string;
  name: string | null;
  version: string | null;
  dependencies: string[];
  enabled: boolean | null;
  installed: boolean | null;
  status: string | null;
  required: boolean | null;
  core: boolean | null;
  stateVersion: number | null;
  module: ModuleRecord | null;
  plugin: PluginRecord | null;
};

export type DependencyNode = {
  id: string;
  label: string;
  known: boolean;
  record: MergedModuleRecord | null;
  dependencies: string[];
  rank: number;
  x: number;
  y: number;
  width: number;
  height: number;
};

export type DependencyEdge = {
  id: string;
  from: string;
  to: string;
  source: DependencyNode;
  target: DependencyNode;
};

export type DependencyGraph = {
  nodes: DependencyNode[];
  edges: DependencyEdge[];
  width: number;
  height: number;
};

export type ObserverSample = {
  measuredAt: string;
  pendingOutbox: number | null;
  processingOutbox: number | null;
  failedOutbox: number | null;
  deadLetterOutbox: number | null;
  processCpuUsage: number | null;
};

export function formatMetric(value: unknown, suffix?: string): string;
export function normalizeStatus(value: unknown): string;
export function mergeModuleRecords(modules?: ModuleRecord[], plugins?: PluginRecord[]): MergedModuleRecord[];
export function buildDependencyGraph(records?: MergedModuleRecord[]): DependencyGraph;
export function appendSample(samples: ObserverSample[], snapshot: unknown, limit?: number): ObserverSample[];
