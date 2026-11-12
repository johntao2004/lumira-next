export type OpenApiDocument = {
  openapi?: string;
  swagger?: string;
  info?: { title?: string; description?: string; version?: string };
  paths?: Record<string, Record<string, unknown>>;
  [key: string]: unknown;
};

const HTTP_METHODS = new Set(["get", "put", "post", "delete", "options", "head", "patch", "trace"]);
export const OPENAPI_DOWNLOAD_FILENAME = "lumira-next-openapi.json";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function operationMatches(path: string, operation: Record<string, unknown>, query: string): boolean {
  const tags = Array.isArray(operation.tags) ? operation.tags.filter((tag): tag is string => typeof tag === "string") : [];
  const searchable = [path, ...tags, JSON.stringify(operation)].join(" ").normalize("NFKC").toLocaleLowerCase();
  return searchable.includes(query);
}

export function countOpenApiOperations(spec: OpenApiDocument): number {
  return Object.values(spec.paths ?? {}).reduce((total, pathItem) => total + Object.keys(pathItem).filter((key) => HTTP_METHODS.has(key.toLowerCase())).length, 0);
}

/** Filter operations by path, tag, summary, description, parameters, and response schema. */
export function filterOpenApiDocument(spec: OpenApiDocument, search: string): { spec: OpenApiDocument; operationCount: number } {
  const query = search.trim().normalize("NFKC").toLocaleLowerCase();
  if (!query) return { spec, operationCount: countOpenApiOperations(spec) };

  const paths: NonNullable<OpenApiDocument["paths"]> = {};
  let operationCount = 0;
  for (const [path, candidate] of Object.entries(spec.paths ?? {})) {
    if (!isRecord(candidate)) continue;
    const matchedOperations = Object.entries(candidate).filter(([method, value]) =>
      HTTP_METHODS.has(method.toLowerCase()) && isRecord(value) && operationMatches(path, value, query));
    if (!matchedOperations.length) continue;
    const pathMetadata = Object.fromEntries(Object.entries(candidate).filter(([key]) => !HTTP_METHODS.has(key.toLowerCase())));
    paths[path] = { ...pathMetadata, ...Object.fromEntries(matchedOperations) };
    operationCount += matchedOperations.length;
  }
  return { spec: { ...spec, paths }, operationCount };
}

export function serializeOpenApiDocument(spec: OpenApiDocument): string {
  return JSON.stringify(spec, null, 2);
}

/** Runtime configuration enforces a read-only API reference. */
export const readOnlyApiDocsConfig = {
  validatorUrl: null,
  oauth2RedirectUrl: null,
  supportedSubmitMethods: [] as string[],
  tryItOutEnabled: false,
  persistAuthorization: false,
  queryConfigEnabled: false,
  withCredentials: false,
  requestSnippetsEnabled: false,
  docExpansion: "none" as const,
  defaultModelsExpandDepth: 0,
  filter: false,
};
