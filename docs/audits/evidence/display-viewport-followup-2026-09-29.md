# Display viewport follow-up — 2026-09-29

**Status:** Authenticated responsive acceptance is blocked by the isolated preview's current login state. No product source was changed and no responsive defect was confirmed.

## Browser state and access boundary

- Inspected the existing Edge tab `1021204276` at `http://127.0.0.1:8020/user/login?returnTo=%2Fdashboard%2Fusers`.
- The visible page is Lumira's username/password login form. The requested `/dashboard/users` workbench route is not open and this tab is not authenticated.
- No credentials were entered, no authentication or write API was called, and no login or form submission was attempted. The existing `8010` tab was not navigated, refreshed, or restarted.
- CUA returned an inline screenshot of the login form during inspection. It does not provide a local screenshot export path, so there is no saved screenshot file for this attempt.

## Current source/build inspection

- `apps/web/src/platform/display/use-display-scale.ts` selects `standard`, `qhd`, and `four-k` from CSS `window.innerWidth` thresholds of 2560px and 3840px; it does not multiply the width by DPR.
- The configured typography/control targets remain: standard 14px body and 24/32/40px controls; QHD 15px and 26/34/42px; 4K 16px and 28/36/44px. The corresponding expanded/collapsed sider widths are 256/64px, 272/72px, and 288/80px.
- Current `apps/web/dist/umi.js` contains the 2560px and 3840px tier checks, and `apps/web/dist/umi.css` contains the `data-display-scale` rules. This confirms matching tier logic in the local build output; it does not establish that port 8020 serves that exact build.
- No build or test command was run during this follow-up.

## Measurements not obtained

Because the preview had no authenticated session, this attempt could not inspect the workbench, navigation, list/search controls, representative drawer, or modal. It also obtained no new runtime values for `window.innerWidth`, `window.devicePixelRatio`, browser zoom, `screen.width`, computed body/control typography, or horizontal overflow at the 1920px, 2560px, and 3840px CSS viewport references. No physical-display evidence was collected.

**Unblock needed:** Open an already authenticated session on the isolated `8020` preview or provide a user-controlled compliant UI sign-in session. Until then, the requested authenticated viewport audit remains incomplete; the login screenshot and source/build inspection are not substitutes for it.
