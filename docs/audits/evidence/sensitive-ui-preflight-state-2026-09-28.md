# Sensitive-word UI acceptance evidence

Date: 2026-09-28 (Asia/Shanghai)

## Scope and safeguards

- All acceptance actions used the isolated authenticated UI tab and visible application controls; no database, direct API, or service restart was used for writes.
- F01 phrases and the tail marker are synthetic test-only text. The 12 persistent Chinese examples are marked as demonstration data, are non-official/non-comprehensive, and all use `LOG_ONLY`.
- The 8 original `qa-sensitive-*` rows and 12 persistent demo rows were not edited or deleted. Earlier synthetic F01 fixture rows were soft-deleted as described below. The current cleanup boundary is limited to active exact marker names; cleanup and post-V15 UI verification are pending because macOS is locked.

## UI paths and observed outcomes

1. Opened the menu path **内容管理 → 敏感词策略** in the isolated Edge tab.
2. The active list after re-importing the test markers showed **41 rows**: 8 pre-existing `qa-sensitive-*` rows, 12 persistent demo rows, 20 active F01 `LOG_ONLY` rows, and 1 active F01 tail `BLOCK` row. All 20 F01 rows were visibly `正常` + `仅记录`; the tail marker was enabled and `BLOCK` for the regression run.
3. In the same policy page's content-check UI, tested synthetic input with the tail BLOCK marker after exactly 20 F01 LOG_ONLY markers. The UI showed 21 total hits, displayed 20 matches, reported the result as blocked, and showed the hidden-blocker message: “阻断规则位于显示清单之外；完整文字扫描仍判定为阻断。” Evidence: [sensitive-f01-hidden-block-2026-09-28.jpg](sensitive-f01-hidden-block-2026-09-28.jpg).
4. Reordered the same synthetic input with the tail BLOCK marker first. The UI showed 21 total hits and a visible BLOCK match in the displayed results, with no hidden-blocker message. Evidence: [sensitive-f01-visible-block-2026-09-28.jpg](sensitive-f01-visible-block-2026-09-28.jpg).
5. Through **运营中心 → 工单反馈 → 提交反馈**, submitted a synthetic title and body containing exactly the 20 F01 LOG_ONLY markers and enabled tail BLOCK marker. The real feedback create form rejected submission with “内容包含敏感词，请修改后再提交”; the list remained “暂无数据”, and cancelling the drawer left no feedback row. The transient toast was observed in the page accessibility state but no feedback screenshot was saved.
6. Reloaded the feedback route after shared-profile QA completed; it remained available and showed the empty feedback list. Returned to sensitive-word management by its menu path. A refresh and final cleanup recount remain pending.

## UI-observed rows and pending exact-marker cleanup

- 8 original `qa-sensitive-*` rows: preserve unchanged.
- 12 Chinese persistent demo rows: preserve enabled and `LOG_ONLY`, from three groups of four (privacy/confidentiality, promotional claims, and transaction risk).
- The earlier UI snapshot showed the 20 `DEMO_ONLY_F01_20260928_LOG_01` through `_LOG_20` rows as active `LOG_ONLY`, and `DEMO_ONLY_TAIL_BLOCK_20260928` as active `BLOCK`. This is historical UI evidence; a later read-only database query below shows one LOG_ONLY name already has no active row.
- `付款前核验收款账户`: temporary single-create test row was soft-deleted through the UI before the current acceptance pass.
- Earlier F01 fixture rows were soft-deleted in an earlier cleanup attempt; the active markers were re-imported through the UI for the feedback submission check. These old soft-deleted fixtures remain in retention and are not visible in the active list.
- Cleanup is exact-name-only: through the UI, delete any remaining active rows among the 20 F01 names and the tail marker, then refresh. Check all 21 exact names individually; each should return zero active rows. The read-only snapshot below predicts 20 rows to delete and an active total of **20** afterward (8 original + 12 demo). If the recount differs, stop without deleting any unmarked row and document the difference.
- The active F01 set and tail remain in the application; no more UI actions were possible while macOS was locked.

### Later read-only database snapshot

At 2026-09-28 12:26 UTC, a read-only query against the local V14 database refined the counts above; this is a pre-V15 database snapshot, not UI cleanup or a post-V15 recount:

- Total active rows: **40**; exact `qa-sensitive-*` legacy rows: **8**; the three documented demo categories: **12**; category `QA_F01_20260928`: **20** active rows.
- The 20 active F01-category rows consist of 19 active `DEMO_ONLY_F01_20260928_LOG_01..20` names and the active `DEMO_ONLY_TAIL_BLOCK_20260928` marker. `LOG_10` already has zero active rows and two soft-deleted copies. Each of the other 19 LOG_ONLY names has one active row and one soft-deleted copy; the tail marker has one active row and no soft-deleted copy.
- `付款前核验收款账户` has zero active rows and one soft-deleted copy. Its stored category is `演示-交易风险`, which has four active demo references and one soft-deleted reference; do not remove that category.
- A category-wide read-only count found no additional rows beyond the exact F01 markers: `QA_F01_20260928` has 20 active and 21 soft-deleted rows; `演示-交易风险` has four active and one soft-deleted row.
- The earlier UI snapshot of 41 active rows predates this count and is retained as historical UI evidence. The current read-only database count is 40; do not delete any extra row to force either count. After deleting only the remaining exact active F01 markers through the UI, the expected active count is 20 (8 legacy + 12 demo).
- V15 was applied successfully in the final shared startup (Flyway V1–V15 all success=true). It seeded `QA_F01_20260928` and every stored category without rewriting sensitive-word rows. After the remaining F01 words are soft-deleted through the UI, delete that category item through the UI only if the reference check confirms no active word uses it. V15 also retains the soft-deleted `付款前核验收款账户` category value; do not delete `演示-交易风险` because active demos still reference it. Soft-deleted word rows remain retained and display can fall back to their raw category value.

## Reusable demo dataset

`/Users/johntao/Documents/GitHub/lumira-next/docs/audits/evidence/sensitive-word-demo-dataset-2026-09-28.json`

The JSON defines 12 understandable neutral Chinese examples, three groups of four. The examples are demonstrations only, not an official or complete vocabulary, and are intended to be imported with action `LOG_ONLY`.

## Screenshots

- `/Users/johntao/Documents/GitHub/lumira-next/docs/audits/evidence/sensitive-f01-hidden-block-2026-09-28.jpg` — 21 total hits, at most 20 shown, blocked by a BLOCK hit outside the displayed list.
- `/Users/johntao/Documents/GitHub/lumira-next/docs/audits/evidence/sensitive-f01-visible-block-2026-09-28.jpg` — visible BLOCK hit among the displayed results.
- No screenshot was captured for the feedback-submit rejection toast or the final cleanup state. The toast and empty list were observed directly through the UI accessibility state; cleanup must be resumed after the foreground slot is released.

## Remaining work

- The original F01 page check is retained as historical evidence, but it is not the final threshold proof under the latest acceptance instruction: a later read-only snapshot shows only 19 active LOG_ONLY markers before the tail BLOCK, so the tested inputs must first establish that the currently active count exceeds the 20-result display cap.
- After the Mac is unlocked, use the sensitive-word UI to add two new, unique, neutral `LOG_ONLY` markers in `QA_F01_20260928`. Do not restore or edit soft-deleted rows. Confirm through the page that there are at least 21 enabled LOG_ONLY markers plus the tail BLOCK, record the exact marker strings and their read-only IDs/category references, then run content detection through the same UI. Acceptance requires the full scan to report blocking, at least 22 total matches, a 20-result display cap, and the hidden-blocker explanation when the tail rule is outside the visible set.
- After that regression, use the UI to soft-delete only the newly created exact markers and any remaining active exact F01/tail markers; refresh and check all 21 original names plus the two new names return zero active rows. Then remove `QA_F01_20260928` through the UI only when its active reference check is clear. Preserve all 8 legacy rows, all 12 demos, and the shared `演示-交易风险` category. Do not restore the prior soft-deleted records or delete an unmarked row to force a count.
- Verify the feedback list remains empty after refresh if a second post-submit persistence observation is required.
- Refresh `/dashboard/sensitive-words`, confirm the observed final active count (expected 20 only if the 8 legacy + 12 demo rows remain unchanged) and zero active rows for every exact test marker, and capture synthetic-only evidence.
- Report the exact current row counts and screenshots to the task coordinator.
