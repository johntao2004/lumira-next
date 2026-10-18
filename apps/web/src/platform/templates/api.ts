import { api, apiBlob, csrf } from "@/platform/api/client";
import type { PageResult } from "@/platform/api/pagination";
import type {
  BindingMode,
  ExportField,
  ImportColumn,
  ImportField,
  ImportPreview,
  ImportTask,
  TemplateDetail,
  TemplateField,
  TemplateSummary,
  TemplateVersion,
} from "./types";

export type TemplateListRequest = {
  page: number;
  pageSize: number;
  search?: string;
  category?: string;
  tag?: string;
  status?: string;
};

async function postMultipart<T>(path: string, form: FormData, signal: AbortSignal, timeoutMs = 15_000) {
  const token = await csrf(signal);
  return api<T>(path, {
    method: "POST",
    headers: { [token.headerName]: token.token },
    body: form,
    signal,
  }, undefined, timeoutMs);
}

export async function createTemplate(file: File, values: {
  name: string;
  category: string;
  tags: string[];
  bindingMode: BindingMode;
}, signal: AbortSignal) {
  const form = new FormData();
  form.append("file", file, file.name);
  form.append("name", values.name);
  form.append("category", values.category);
  for (const tag of values.tags) form.append("tags[]", tag);
  form.append("bindingMode", values.bindingMode);
  return postMultipart<TemplateDetail>("/platform/templates", form, signal, 180_000);
}

export async function replaceTemplateSource(templateId: string, version: number, file: File, signal: AbortSignal) {
  const form = new FormData();
  form.append("file", file, file.name);
  form.append("version", String(version));
  return postMultipart<TemplateDetail>(`/platform/templates/${templateId}/versions/draft/source`, form, signal, 180_000);
}

export async function getTemplateFields(signal: AbortSignal) {
  return api<TemplateField[]>("/platform/templates/fields", { signal });
}

export async function getTemplate(templateId: string, signal: AbortSignal) {
  return api<TemplateDetail>(`/platform/templates/${templateId}`, { signal });
}

export async function getTemplateVersions(templateId: string, signal: AbortSignal) {
  return api<TemplateVersion[]>(`/platform/templates/${templateId}/versions`, { signal });
}

export async function listTemplates(request: TemplateListRequest, signal: AbortSignal) {
  const query = new URLSearchParams({ page: String(request.page), pageSize: String(request.pageSize) });
  for (const key of ["search", "category", "tag", "status"] as const) {
    const value = request[key]?.trim();
    if (value) query.set(key, value);
  }
  return api<PageResult<TemplateSummary>>(`/platform/templates?${query}`, { signal });
}

export async function saveTemplateBindings(templateId: string, version: number, bindings: Record<string, string>, signal: AbortSignal) {
  return api<TemplateDetail>(`/platform/templates/${templateId}/versions/draft`, {
    method: "PUT",
    ...(await jsonRequest({ version, bindings }, signal)),
  });
}

async function jsonRequest(body: unknown, signal: AbortSignal) {
  const token = await csrf(signal);
  return {
    headers: { "Content-Type": "application/json", [token.headerName]: token.token },
    body: JSON.stringify(body),
    signal,
  };
}

export async function updateTemplate(templateId: string, values: { version: number; name: string; category: string; tags: string[] }, signal: AbortSignal) {
  const token = await csrf(signal);
  return api<TemplateDetail>(`/platform/templates/${templateId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", [token.headerName]: token.token },
    body: JSON.stringify(values),
    signal,
  });
}

export async function templateAction(templateId: string, action: "publish" | "deactivate", version: number, signal: AbortSignal) {
  const token = await csrf(signal);
  return api<TemplateDetail>(`/platform/templates/${templateId}/${action}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", [token.headerName]: token.token },
    body: JSON.stringify({ version }),
    signal,
  });
}

export async function templatePreview(templateId: string, body: {
  templateVersion: number;
  userId?: string;
  values: Record<string, string>;
}, signal: AbortSignal) {
  const token = await csrf(signal);
  return apiBlob(`/platform/templates/${templateId}/preview`, {
    method: "POST",
    headers: { "Content-Type": "application/json", [token.headerName]: token.token },
    body: JSON.stringify(body),
    signal,
  }, 180_000);
}

export async function getImportFields(signal: AbortSignal) {
  return api<ImportField[]>("/platform/users/import/fields", { signal });
}

export async function getExportFields(signal: AbortSignal) {
  return api<ExportField[]>("/platform/users/export/fields", { signal });
}

export async function inspectImportColumns(file: File, signal: AbortSignal) {
  const form = new FormData();
  form.append("file", file, file.name);
  return postMultipart<{ columns: ImportColumn[] }>("/platform/users/import/columns", form, signal, 180_000);
}

export async function previewUserImport(file: File, mapping: Record<string, string>, mode: "create" | "update" | "upsert", signal: AbortSignal) {
  const form = new FormData();
  form.append("file", file, file.name);
  form.append("mapping", JSON.stringify(mapping));
  form.append("mode", mode);
  return postMultipart<ImportPreview>("/platform/users/import/preview", form, signal, 300_000);
}

export async function commitUserImport(previewId: string, previewVersion: number, idempotencyKey: string, signal: AbortSignal) {
  const token = await csrf(signal);
  return api<ImportTask>(`/platform/users/import/${previewId}/commit`, {
    method: "POST",
    headers: { "Content-Type": "application/json", [token.headerName]: token.token },
    body: JSON.stringify({ previewVersion, idempotencyKey }),
    signal,
  }, undefined, 600_000);
}

export async function getUserImportTask(taskId: string, signal: AbortSignal) {
  return api<ImportTask>(`/platform/users/import/tasks/${taskId}`, { signal });
}

export async function getUserImportTemplate(signal: AbortSignal) {
  return apiBlob("/platform/users/import/template.xlsx", { signal }, 60_000);
}

export async function getUserImportErrors(previewId: string, signal: AbortSignal) {
  return apiBlob(`/platform/users/import/${previewId}/errors.xlsx`, { signal }, 180_000);
}

export async function exportUsersXlsx(body: {
  scope: "currentPage" | "selected" | "filtered";
  page: number;
  pageSize: number;
  userIds: string[];
  search?: string;
  filters: Record<string, string>;
  filterModes: Record<string, "exact" | "contains" | "range">;
  fields: string[];
  fieldLabels?: Record<string, string>;
  statusLabels?: Record<"true" | "false", string>;
}, signal: AbortSignal) {
  const token = await csrf(signal);
  return apiBlob("/platform/users/export.xlsx", {
    method: "POST",
    headers: { "Content-Type": "application/json", [token.headerName]: token.token },
    body: JSON.stringify(body),
    signal,
  }, 300_000);
}

export async function printUsers(body: {
  templateId: string;
  templateVersion: number;
  userIds: string[];
  format: "pdf" | "zip";
  merge: boolean;
}, signal: AbortSignal) {
  const token = await csrf(signal);
  return apiBlob("/platform/users/template-print", {
    method: "POST",
    headers: { "Content-Type": "application/json", [token.headerName]: token.token },
    body: JSON.stringify(body),
    signal,
  }, 600_000);
}
