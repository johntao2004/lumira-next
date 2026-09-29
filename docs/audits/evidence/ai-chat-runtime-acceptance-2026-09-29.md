# AI chat and model runtime acceptance — 2026-09-29

## Status

**Preflight only.** No browser slot was assigned to this executor, and no chat-capable model was available for safe inference. Chat generation, cancel behavior, real model discovery, and AI translation remain untested in the product UI.

## Model and reachability

- The latest saved UI evidence in `docs/audits/ai-chat-and-model-ui-2026-09-28.md` and the 2026-09-29 follow-up in `docs/audits/evidence/ui-acceptance-2026-09-28/03-ops-settings-locale.md` records one enabled `Jev` / `TypeSafe Jev` / `jev-latest` profile with `CHOICE`, `SCORE`, and `NOUL`; `CHAT=0`. This executor did not re-read provider rows, open credentials, or contact an endpoint.
- Local inventory: `ollama` is absent from `PATH`; LM Studio's `lms` CLI is installed. `lms ls --llm --json` reports one installed LLM, `mys/laya-multilingual-GGUF/laya_multilingual_q8_0.gguf` (`Laya Multilingual`, 361,712,736 bytes, Q8_0, `ggmlc`, max context 4096, vision false, `trainedForToolUse=false`) and a separate embedding model. The model is not loaded. The metadata does not establish CHAT capability.
- `lms ps` reported no loaded models. `lms status` and `lms server status` reported the inference server off; ports 11434 and 1234 had no listener. `lms ls` printed that it was waking the LM Studio management service, but no model was loaded and the inference server remained off. No local inference or remote provider request was made.
- No model was loaded, downloaded, or installed. No provider was created or edited. No charge occurred. Executor context: `gpt-6-luna / max`.

## Source review

- The provider UI exposes model discovery. The backend discovery path calls the configured provider's `/models` endpoint; actual chat uses `/chat/completions`. `TypeSafe Jev` is excluded from chat by provider kind and capability checks. See `apps/web/src/features/ai/ai-models-page.tsx`, `backend/src/main/java/com/lumira/platform/ai/ModelConnectionService.java`, and `backend/src/main/java/com/lumira/platform/ai/AiAssistantService.java`.
- The console submits at most the latest 20 transcript messages; the backend caps each message at 5,000 characters. The UI keeps sessions in component state, and the existing 2026-09-28 browser evidence says a blank conversation reset after reload. The sender's cancel handler aborts the request and restores the prior transcript and draft; that behavior still needs visible runtime evidence with a reachable CHAT model.
- The localization AI dialog has separate provider/model selectors, a visible preview, batch progress, cancel/retry, and an explicit save step. A real model response must appear in the preview before translation can be accepted. No translation data was changed in this preflight.
- Source review found a credential-rule mismatch: `AiProviderProfile.requiresCredential()` returns false for Ollama and LM Studio, while `LocalizationAiService.usable()` checked for a configured secret for every provider. The localization gate now checks secret state only when `provider.requiresCredential()` is true (`LocalizationAiService.java:105-109`). This leaves hosted providers credential-gated and allows a credential-free local CHAT provider to appear in localization. The isolated regression covers local translation, hosted-without-credential rejection, a hosted provider with a configured credential, and Jev exclusion (`LocalizationAiServiceTest.java:27-79`).

## Independent AI-center pages outside chat generation

The platform manifest registers `/dashboard/ai-console`, `/dashboard/ai-models`, `/dashboard/ai-judgment`, and `/dashboard/ai-capabilities` as separate routes (`apps/web/src/features/platform/manifest.ts:8-14`). The non-chat acceptance items are:

- `/dashboard/ai-models`: inspect provider rows without opening credential fields; after a user-saved safe provider is confirmed, check editable default base URL, model discovery, selecting a discovered ID, save/refresh persistence, and the visible discovery-failure message. Discovery and provider test call the configured provider's `/models`; only use them against a confirmed no-charge local endpoint.
- `/dashboard/ai-capabilities`: read-only check of connected versus planned capabilities, operations, permission labels, links, query failure/retry, pagination, and narrow-table scrolling. The page requests platform capability metadata and does not invoke a model.
- `/dashboard/ai-judgment`: this is structured CHOICE/SCORE/NOUL inference, separate from ordinary chat. Its Run action calls `/platform/ai/judgments` and reaches the hosted Jev endpoint; layout/form review can be separate, but no inference request is authorized in this no-charge pass.

These are source-derived acceptance items, not new browser evidence.

## UI acceptance preconditions

1. The user confirms the CHAT model was saved through `/dashboard/ai-models`, with an exact model identifier and a known no-charge endpoint. For a local endpoint, its inference server must be running with a confirmed chat-capable model loaded. The installed `Laya Multilingual` model is not assumed to meet that condition; no credential is requested or entered by this executor.
2. The coordinator assigns the browser slot. Recheck the saved provider row through the visible UI without opening credential fields. Verify discovery, model selection, save, refresh, and any failed-discovery message through the page. Use provider test only after confirming the endpoint is a safe no-charge path.
3. At `/dashboard/ai-console`, use the authenticated administrator session at 942×794 CSS px with the sidebar expanded. Check visible provider/model selection, a real first and follow-up turn, long CJK and unbroken content, cancel/draft restoration, error behavior if safely available, session switching, and page reload. Record whether the transcript persists.
4. At `/dashboard/localization`, run a real translation for uniquely marked synthetic content, inspect the visible preview, save the selected result, refresh, and verify the translation consumer. Use a new synthetic key/locale or an exact baseline with a normal UI restore path; retain the unique marker for coordinator cleanup and do not overwrite unrelated translations.

## Evidence not collected

No browser identity or viewport was observed in this pass. There is no current product UI response, discovery list, conversation, cancellation, reload result, translation preview, or screenshot path. The targeted Java 25 command `cd backend && ./mvnw -Dtest=LocalizationAiServiceTest test` passed: 1 test, 0 failures, 0 errors, 0 skipped. Maven recompiled the backend sources and tests. No full backend verification, frontend build, service restart, live provider request, or model inference was run. No charge occurred. Browser acceptance remains pending the user's CHAT configuration and the coordinator's explicit browser handoff.
