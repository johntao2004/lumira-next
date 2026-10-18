import type { ModelOption, ProviderKind } from "./types";

export const documentFormats = ["txt", "md", "markdown", "doc", "docx", "pdf", "csv", "json", "log"];
export const imageFormats = ["png", "jpg", "jpeg"];
export const attachmentFormats = [...documentFormats, ...imageFormats];

export function defaultFileFormats(kind?: ProviderKind, model?: ModelOption | string): string[] {
  if (kind === "TYPESAFE_JEV") return [];
  if (typeof model === "object" && model.supportedFileFormats) return model.supportedFileFormats;
  const id = typeof model === "string" ? model : model?.id;
  const image = typeof model === "object" && model.inputModalities.includes("image")
    || kind === "DEEPSEEK" && ["deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp"].includes(id ?? "");
  return image ? [...attachmentFormats] : [...documentFormats];
}

export function acceptsFile(formats: readonly string[], name: string): boolean {
  return formats.includes(name.slice(name.lastIndexOf(".") + 1).toLowerCase());
}

export function modelFileFormats(model?: ModelOption): string[] {
  return model ? model.supportedFileFormats ?? defaultFileFormats(undefined, model) : [];
}
