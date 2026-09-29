# Ordinary-user acceptance and synthetic cleanup status — 2026-09-29

## Current browser/session check

- Read the exact Edge tab `1021204276` by its provider tab ID without navigating it. Its current route is `http://127.0.0.1:8020/user/login?returnTo=%2Fdashboard%2Fusers`. The fresh accessibility tree shows the Lumira login form, empty `用户名` and `密码` fields, and the `登录` button.
- Opened an isolated Edge tab `1021204339` directly to the 8020 users route. It redirected to the same login route and showed the same empty login form. Closed that temporary tab after inspection; the original 8020 tab remained unchanged. No credentials were entered, generated, read, or submitted. No user or permission was created or changed.
- The browser inventory showed no authenticated Lumira tab available to this subtask. The separate 8010 tab was not opened, refreshed, navigated, or controlled. The normal startup/status helper was not run because it probes 8010.
- CUA exposed the current accessibility state inline; it did not provide a project-file screenshot export path, so no standalone screenshot is claimed for this read-only check.

## Ordinary-user acceptance

- Existing evidence says the prepared synthetic QA identity is `qa_quota_ui_202609281638519dn7` / `Quota UI QA 202609281638519dn7`. It remains uncreated: the password field was left blank and the save action was not taken. The preserved handoff asks the user to enter a new temporary password, save, and complete the Lumira login themselves; only a non-secret completion signal is needed.
- Therefore ordinary-user data isolation, feedback submission/range and 403 recovery, profile-field value selection/persistence, and ordinary-user file/quota boundary checks remain blocked on that user action. Do not infer them from the earlier administrator session or source-only analysis.
- Earlier administrator UI evidence remains valid as historical evidence: the zero-authority `SELF` quota role was created/edited/refreshed/toggled, the empty parent-child organization tree was saved/refreshed, and two administrator-owned quota fixtures were uploaded/refreshed/downloaded. That evidence does not prove ordinary-user behavior.

## Exact synthetic object inventory and safe cleanup boundary

| Object | Exact evidence | Safe normal-UI path / current disposition |
|---|---|---|
| Quota role `QA_SELF_QUOTA_20260928_181851` | `b371fb27-1ca5-461b-b25f-618b484ebb55`; prior read-only evidence: zero authorities and zero user assignments | UI deletion reaches a hard delete with no restore path. Skipped as potentially irreversible. |
| Parent organization `QA_ORG_20260928_181851_P` | `79953086-e3b5-451c-8a72-993ccda8b878`; prior evidence: zero members, one synthetic child | UI deletion is a hard delete; child must be removed first. Skipped as potentially irreversible. |
| Child organization `QA_ORG_20260928_181851_C` | `99f904ff-1f26-4e1e-ae02-d5a39023e6b0`; prior evidence: zero members | UI deletion is a hard delete with no restore path. Skipped as potentially irreversible. |
| Imported sensitive words | `ALPHA` `580ca365-b029-4e83-8a70-52ab726b5a50`; `WITH,COMMA` `ce4b13b4-1ede-4908-8cc0-ba44d680da6d`; `WITH "QUOTE"` `d7f72796-7fe2-4479-8d91-f475ecab34f0` | Each row has a UI delete action backed by soft-delete. No current authorized admin page was available to verify the rows immediately before deletion, so none was deleted. The import marker `22bd37a2-cf46-418e-afb9-d9a79b16a96c` is the import audit-event ID, not a UI-manageable batch row; audit history is retained. |
| Feedback `QA_ADMIN_FEEDBACK_LIFECYCLE_20260929_A` | Prior UI evidence: created 2026-09-29 05:49 by administrator, unique result under exact title filter, current status “处理中”; UUID not captured | Feedback page has no row delete control. Current row cannot be checked without an authenticated session. Skipped; do not search outside the recorded UI evidence or remove audit/history. |
| `en-QA` locale and `localizationBundleFormat` override | Prior UI evidence: value `QA en-QA B — language package format`; package A duplicate check preserved B | UI has a per-entry “restore default” control for an overridden translation, but no locale-delete control. Current authorized page is unavailable; no action taken. Do not claim the locale itself can be removed through the UI. The old `zh-CN/sensitiveImportCsvChooseFile` override is already restored and reloaded per the follow-up evidence. |
| `lumira-quota-ui-20260928-181851.txt` | file `c85825d9-9901-4e79-8938-f85e54deb766`, 126 bytes, administrator-owned synthetic fixture | Personal Files UI offers soft-delete to Trash and a restore action. No authenticated UI was available for fresh identity check or deletion, so it remains untouched. |
| `lumira-quota-chunk-boundary-20260929.txt` | file `d956b884-ddd4-43c7-8c7d-fc4439738553`, 131,072 bytes, administrator-owned synthetic fixture | Personal Files UI offers soft-delete to Trash and a restore action. No authenticated UI was available for fresh identity check or deletion, so it remains untouched. Permanent purge and async usage-refresh remain deferred. |

The previously observed `retention-policy-1790620230082.txt` is not treated as a cleanup candidate: it disappeared from the list after another upload before this subtask, and its disappearance cause is unverified. The five protected preview files and two baseline files were not touched. No direct API or database writes were used; no retention value or file state was changed.

## Blocker and next user action

The 8020 page currently offers only the login form, and the pre-existing QA handoff still needs the user to set and submit a temporary password before ordinary-user checks can start. After the user completes that handoff, continue on the authorized session, re-check each candidate by its exact marker immediately before acting, use only a recoverable UI workflow, and reload to verify absence. Keep hard-delete role/organization objects, the feedback record without a delete control, and the locale itself out of cleanup.

## Evidence sources

- [UI acceptance matrix](../ui-acceptance-2026-09-28.md)
- [Quota/users UI evidence](ui-acceptance-2026-09-28/01-quota-users.md)
- [Sensitive-word/UI evidence](ui-acceptance-2026-09-28/02-monitor-dict-sensitive.md)
- [Operations/localization evidence](ui-acceptance-2026-09-28/03-ops-settings-locale.md)
- [2026-09-29 session follow-up](qa-session-followup-2026-09-29.md)
