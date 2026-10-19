/** 生成搜索表单的完整同步快照；保留日期实例，签名只用于决定何时同步。
 * Build a complete search-form snapshot; retain date instances and use the signature only to decide when to synchronize.
 */
export function resourceSearchSnapshot(requestValues: Record<string, unknown>, overrides?: Record<string, unknown>) {
  const values = { ...requestValues, ...overrides };
  const describe = (value: unknown): unknown => {
    if (value === null || typeof value !== "object") return [typeof value, value];
    if ("toISOString" in value && typeof value.toISOString === "function") {
      try { return ["date", value.toISOString()]; } catch { return ["date", "invalid"]; }
    }
    if (Array.isArray(value)) return ["array", value.map(describe)];
    return ["object", Object.keys(value).sort().map(key => [key, describe((value as Record<string, unknown>)[key])])];
  };
  return { values, key: JSON.stringify(describe(values)) };
}
