export interface FeedbackDraftValues {
  title: string;
  priority: string;
  category: string;
  detailHtml: string;
}

/** Persist only the fields needed to create feedback; never store server state. */
export function encodeFeedbackDraft(values: Partial<FeedbackDraftValues>): { schema: 1; values: FeedbackDraftValues } {
  return {
    schema: 1,
    values: {
      title: typeof values.title === "string" ? values.title : "",
      priority: typeof values.priority === "string" ? values.priority : "",
      category: typeof values.category === "string" ? values.category : "",
      detailHtml: typeof values.detailHtml === "string" ? values.detailHtml : "",
    },
  };
}

/** Validate untrusted draft data before putting it back into the editor. */
export function decodeFeedbackDraft(payload: unknown): FeedbackDraftValues | null {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
  const envelope = payload as Record<string, unknown>;
  if (envelope.schema !== 1 || !envelope.values || typeof envelope.values !== "object" || Array.isArray(envelope.values)) return null;
  const values = envelope.values as Record<string, unknown>;
  if (
    typeof values.title !== "string" || values.title.length > 160 ||
    typeof values.priority !== "string" || values.priority.length > 64 ||
    (values.category !== undefined && (typeof values.category !== "string" || values.category.length > 64)) ||
    typeof values.detailHtml !== "string" || values.detailHtml.length > 1_000_000
  ) return null;
  return { title: values.title, priority: values.priority, category: typeof values.category === "string" ? values.category : "", detailHtml: values.detailHtml };
}
