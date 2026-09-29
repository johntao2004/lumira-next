# Platform settings consumers and maintenance final gate — 2026-09-29

## Result

**Maintenance final gate: blocked and not activated.** Existing acceptance evidence still leaves the ordinary-user role/quota upload boundaries and feedback scope/recovery open. The 2026-09-28 settings review explicitly requires maintenance to be the final settings check after quota and ordinary-user flow, with original values restored through the UI.

**Independent settings consumers: not exercised.** No current authenticated settings form was available to capture an exact baseline and matching UI restore path. Branding footer, watermark, and agreement settings were left untouched.

## Evidence reviewed

- `file-preview-and-download-followup-2026-09-29.md` (Remaining acceptance items): the synthetic ordinary-user account was not created; the planned `qa_storage_20260929` and `qa_count_20260929` roles did not exist; ordinary-user role/quota upload boundaries and feedback scope/recovery remained open.
- `system-ui-settings-review-2026-09-28.md` (settings acceptance matrix): administrator/ordinary-user feedback scope and missing-category behavior were not yet accepted; protocol and maintenance consumer paths were not yet accepted; maintenance was required to wait until quota and ordinary-user flow were complete.
- `ui-acceptance-2026-09-28/02-monitor-dict-sensitive.md` records an earlier read-only baseline: agreements empty; maintenance title/message/end time blank, no allowed roles, disabled; SMTP and SMS disabled. This is historical evidence only and was not treated as a current baseline.

## Current browser access and actions

- CUA inventory showed an existing Edge tab at `http://127.0.0.1:8020/dashboard/users`, already owned by another browser session. Binding it failed with “already part of browser session”; it was not inspected or changed.
- A separate Edge tab opened to `http://127.0.0.1:8020/dashboard/branding` redirected to `/user/login?returnTo=%2Fdashboard%2Fbranding`. Its visible UI was the login form, with no authenticated settings values available.
- The `8010` tab appeared in inventory at `/user/login?returnTo=%2Fdashboard%2Fusers`; it was not navigated, refreshed, or otherwise interacted with. No project service was refreshed or restarted.
- No settings values were edited or saved. No API or database writes were used. No accounts, passwords, permissions, roles, recipients, SMTP settings, or SMS settings were changed; no messages were sent.

## Restore proof

No website settings write occurred, so there is no mutation to restore. The baseline and restore path required for an independent consumer test could not be established in the current authenticated UI session. Maintenance remains disabled by this run: it was neither enabled nor saved.

## Next acceptance order

1. Complete and document ordinary-user role/quota and feedback scope/recovery UI acceptance.
2. Obtain a released authenticated settings tab and capture current values in the rendered forms.
3. Exercise only consumers whose exact original values and matching UI restore operation are visible and verifiable; reload after restoration.
4. Run the maintenance final gate last: save a short neutral test title/description and an already-allowed role only as warranted, verify logged-in behavior, restore the captured original values through the UI, reload, and verify admin and ordinary-user behavior.
