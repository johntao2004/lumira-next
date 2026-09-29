# Feedback category dictionary UI acceptance — 2026-09-28

## Browser/session scope

- Target: isolated Edge tab `1021203983`, local app `http://127.0.0.1:8010`.
- Pages reached through the visible menu: **Content Management → Dictionary Management → Work Order Feedback Category → Dictionary Items** and **Operations → Work Order Feedback**.
- No API/curl/SQL/browser-session injection was used for acceptance. No service was restarted and no build was run. No credentials or account identifiers are recorded here.
- Screenshot: `tab.screenshot()` was scoped to the target tab and emitted inline during interaction, but there is no safe local-file export in this tool path. No screenshot file was saved; no desktop capture was used.

## Actions and visible results

| Step | UI action | Observed result |
|---|---|---|
| Baseline | Open category dictionary item table | Five seeded rows were present before the temporary test item was introduced; `QA_TEMP_CATEGORY_20260928` was absent. |
| Create/edit/order | Through **新增字典项**, create the unique synthetic value; edit only its label and sort order | The test value remained `QA_TEMP_CATEGORY_20260928`; label was changed to `QA 临时分类已改名 20260928`; sort order was set to `15`. The stable value field was read-only while editing. |
| Consumer while enabled | Open feedback create form and category list | The option displayed `QA 临时分类已改名 20260928` and the option value was `QA_TEMP_CATEGORY_20260928`. The enabled create option was directly observed. The enabled filter dropdown was not opened; source inspection shows the filter uses the same options catalog. |
| Refresh enabled state | Reload the item page | The edited label and sort order remained visible, ordered between `QUESTION` (10) and `BUG` (20). |
| Draft linkage | Select the test category; fill title `QA_SYNTHETIC_DRAFT_RESTORE_20260928` and details `Synthetic UI validation only. No personal data.`; click **保存草稿** | Draft UI displayed a saved timestamp and **恢复草稿** / **删除草稿** actions. Initial form content was synthetic only. |
| Refresh and restore | Reload the feedback route, reopen **提交反馈**, click **恢复草稿**, confirm its popover | The saved title and renamed category label were restored. The UI had a manual restore action; no saved feedback record was submitted. The editor's detail content was entered before saving, but post-restore rich-text content was not independently read back from the accessibility tree. |
| Draft cleanup | Use **删除草稿** and confirm; close the form | Restore/delete controls disappeared; the feedback table remained empty. The draft was removed in the UI. |
| Disable | Edit the exact test item, switch it off, save, reload item table | The row showed **停用** both immediately and after reload. |
| Disabled consumers | Reopen feedback filter and create form dropdowns | `QA_TEMP_CATEGORY_20260928` and its label were absent from both dropdowns; enabled categories remained. |
| Final cleanup | In the disabled item row only, choose **删除**, confirm the named test item, reload | The category item table returned to **共 5 条**, showing only `QUESTION`, `BUG`, `FEATURE`, `ACCOUNT`, and `OTHER`. The test code was absent after refresh. No other dictionary item or feedback record was deleted. |

## Evidence limits

- Code/source evidence and UI evidence are separate. Source paths, stable-value behavior, API validation, cache invalidation and policy boundaries are listed in `dictionary-coverage-inventory-2026-09-28.md`.
- Runtime evidence: the local dictionary and feedback pages loaded and the visible controls completed the listed flows in this tab. This file does not claim a build, backend test run, health-script run, or saved feedback-row persistence test.
- There is no feedback-row delete action in the UI used here, so no feedback record was created. Category-to-draft selection and restore were verified; persisted feedback row/linkage was deliberately left untested.
- Profile-field dictionary consumers, sensitive-word policies, role/permission pages, AI settings and localization registry were source-inspected only, not opened in the browser during this task.
