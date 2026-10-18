export type TemplateStatus = "draft" | "published" | "inactive";
export type BindingMode = "auto" | "manual";

export type TemplateField = {
  key: string;
  label: string;
  type: string;
  source: string;
  readable: boolean;
};

export type TemplateSlot = {
  key: string;
  label: string;
  text: string;
};

export type TemplateVersion = {
  id: string;
  version: number;
  status: "draft" | "published" | "superseded";
  filename: string;
  sha256: string;
  slots: TemplateSlot[];
  placeholders: string[];
  bindings: Record<string, string>;
  createdAt: string;
  createdBy: string;
};

export type TemplateSummary = {
  id: string;
  name: string;
  category: string;
  tags: string[];
  status: TemplateStatus;
  version: number;
  draftVersion: number | null;
  publishedVersion: number | null;
  updatedAt: string;
};

export type TemplateDetail = TemplateSummary & {
  currentVersion: TemplateVersion | null;
};

export type ExportField = {
  key: string;
  label: string;
  type: string;
  source: string;
  readable: boolean;
};

export type ImportField = {
  key: string;
  label: string;
  type: string;
  source: string;
  writable: boolean;
};

export type ImportColumn = { name: string; index: number };

export type ImportPreviewRow = {
  rowNumber: number;
  action: "create" | "update" | "invalid";
  matchedUserId: string | null;
  expectedUserVersion: number | null;
  changes: Record<string, { before: unknown; after: unknown }>;
  errors: string[];
};

export type ImportPreview = {
  previewId: string;
  previewVersion: number;
  fileHash: string;
  expiresAt: string;
  rows: ImportPreviewRow[];
};

export type ImportTaskRow = {
  rowNumber: number;
  action: "create" | "update" | "failed";
  username?: string;
  errors: string[];
};

export type ImportTask = {
  taskId: string;
  status: "running" | "completed" | "failed";
  errorCode?: string | null;
  created: number;
  updated: number;
  failed: number;
  rows: ImportTaskRow[];
};
