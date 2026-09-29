# Display scale calibration — 2026-09-29

**Status:** Source patch applied and frontend typecheck passed. Login-page and CSS-viewport checks passed at simulated reference widths; authenticated workbench, list, editor, drawer, modal and real physical-monitor/DPR checks remain incomplete. The original static-review notes below describe the pre-application state.

## Applied reduced-size targets

The draft uses the usable CSS viewport width only. DPR and physical screenshot dimensions do not promote a window to a larger UI tier.

| Reference CSS viewport | Tier | Body / Ant Design font | Page title | Login title | Controls: small / default / large | Form gap | Sider: open / collapsed | Avatar |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1080p, about 1920×1080 | standard, `< 2560px` | 14px | 20px | 32px | 24 / 32 / 40px | 16px | 256 / 64px | 28px |
| QHD / 2K, about 2560×1440 | qhd, `2560–3839px` | 15px | 24px | 34px | 26 / 34 / 42px | 18px | 272 / 72px | 30px |
| UHD / 4K, about 3840×2160 | four-k, `≥ 3840px` | 16px | 28px | 38px | 28 / 36 / 44px | 20px | 288 / 80px | 32px |

These are CSS viewport reference widths, not claims about the current physical monitor. Record `window.innerWidth`, `window.devicePixelRatio`, the observed browser zoom, and `screen.width` separately during acceptance.

## Pre-patch oversized baseline and applied targets

Before this patch, at DPR 1, the hook selected `standard < 1900`, `two-k ≥ 1900`, `qhd ≥ 2500`, and `four-k ≥ 3800` from `innerWidth × DPR`. The CSS file also repeated tier presentation rules under both 1920/2560/3840 media queries and `data-display-scale` selectors. The user's current complaint is that 2K/4K typography and controls are oversized; the applied 15px/16px body targets reduce the prior 18px/20px values.

| CSS viewport at DPR 1 | Pre-patch oversized tier and values | Applied reduced-size tier and values |
|---|---|---|
| 1920px (1080p reference) | two-k; body 15px, controls 28/36/44px, page title 24px, login title 36px, login fields 52px, sider 272px | standard; body 14px, controls 24/32/40px, page title 20px, login title 32px, login fields 44px, sider 256px |
| 2560px (QHD / 2K reference) | qhd; body 18px, controls 32/42/50px, page title 32px, login title 44px, login fields 58px, sider 312px | qhd; body 15px, controls 26/34/42px, page title 24px, login title 34px, login fields 48px, sider 272px |
| 3840px (UHD / 4K reference) | four-k; body 20px, controls 36/46/56px, page title 38px, login title 52px, login fields 66px, sider 360px | four-k; body 16px, controls 28/36/44px, page title 28px, login title 38px, login fields 50px, sider 288px |

Current DPR false promotions include 1280 CSS px × DPR 2 → qhd (18px body), 960 CSS px × DPR 2 → two-k, and 650 CSS px × DPR 3 → two-k. In the draft, each remains standard because its usable CSS width is below 2560px.

## Patch scope

The patch file is [display-scale-calibration-2026-09-29.patch](display-scale-calibration-2026-09-29.patch). It changes only:

- `apps/web/src/platform/display/use-display-scale.ts`: remove the extra two-k tier, remove DPR multiplication, and select standard/QHD/4K from CSS viewport breakpoints 2560px and 3840px.
- `apps/web/src/app/globals.css`: replace duplicated display media/data rules with one set keyed by `data-display-scale`, align body size with the shared token, and reduce large-screen title/control/spacing expansions.

The existing shared consumers remain unchanged: `apps/web/src/platform/providers.tsx` feeds the resolved size into Ant Design's font/control/form tokens; `apps/web/src/platform/shell/workspace.tsx` feeds sider/avatar sizes and keeps its separate 767px mobile-shell decision. `apps/web/src/app.tsx` continues loading the global stylesheet. The global centered-modal setting, workbench greeting, theme, and model drawers are outside this patch.

## Static draft checks completed before application

- `patch --dry-run -p1 -i docs/audits/display-scale-calibration-2026-09-29.patch` succeeded against the current checkout. This checks that the reviewable patch matches the current sources without applying it.
- An independent Python check applied the patch only to a temporary copy, then verified the 2560/3840 CSS-width thresholds, removal of DPR multiplication and the old `two-k` tier, the proposed typography values, and identical tiers for DPR 1/2/3 at a fixed CSS width. It checked 1920, 2559, 2560, 3839, 3840 CSS px; 1280×DPR2, 960×DPR2, 650×DPR3; and mobile widths 320, 375, 390, 430, 767 CSS px. All assertions passed, and a separate assertion confirmed the application source files remain unchanged.
- A first text-only assertion looked only at `+` lines in the unified diff and failed because a desired value (`24px`) was unchanged context reused from the old CSS. The check was corrected to apply the patch to temporary copies and inspect the resulting files; the corrected check passed.
- The checkout's frontend/backend health was read as HTTP 200 before preparing the draft; that is service health only, not UI acceptance.
- No application source was written, no build or Umi setup ran, and no browser page was refreshed or operated. No typecheck or browser screenshot has run against the proposed patch.

## Applied patch and browser evidence

- Applied `display-scale-calibration-2026-09-29.patch` to `apps/web/src/platform/display/use-display-scale.ts` and `apps/web/src/app/globals.css` after confirming the open login form had no entered values. This reduces oversized 2K/4K typography and controls; the dev server hot-reloaded, with no service restart.
- `pnpm --dir apps/web typecheck` exited 0. Frontend and backend health both returned HTTP 200.
- At the actual browser state, the login page measured 1912×802 CSS px at DPR 2. The resolved tier is `standard`; computed body font is 14px and H1 is 32px. The document had no horizontal overflow and all visible input values were empty. This is a browser CSS viewport measurement, not proof of physical 4K mode.
- On isolated Lumira test tab `1021204253`, viewport emulation produced the following read-only measurements:

| Simulated CSS viewport | Tier | Body / H1 | Horizontal overflow |
|---|---|---|---|
| 1920×1080 | standard | 14px / 32px | none; scroll width 1909px |
| 2559×1440 | standard | 14px / 32px | none; scroll width 2548px |
| 2560×1440 | qhd | 15px / 34px | none; scroll width 2549px |
| 3839×2160 | qhd | 15px / 34px | none; scroll width 3828px |
| 3840×2160 | four-k | 16px / 38px | none; scroll width 3829px |
| 767px, 390px, 320px widths | standard | as standard tier | none |

- Emulation forced DPR 1. It validates CSS-width breakpoints only, not behavior at DPR 2/3 or a physical 2K/4K monitor. The default browser was restored to 1912×802 CSS px at DPR 2 after the check.
- Direct visits to `/dashboard/home` and `/dashboard/users` redirected to `/user/login?returnTo=...`. No authentication was entered. Workbench/list/editor/drawer sizing remains unverified. Global modal centering is a separate audit item, is not included in this font patch, and remains unverified/unapplied. The QA agent provided inline screenshots, but CUA did not save screenshot files.
- The real 1912px CSS viewport at DPR 2 correctly resolves to `standard` under the applied CSS-viewport policy. This verifies the actual login page at that browser viewport, not a physical 2K/4K monitor or its simulated reference tiers.

## Remaining browser acceptance

Capture before/after evidence on the exact route and auth state; do not change system resolution or browser zoom to manufacture a result.

1. Measure and record CSS viewport, DPR, browser zoom, and physical screen size separately.
2. Inspect 1920×1080, 2560×1440, and 3840×2160 CSS viewport references. If the physical display/browser cannot expose one as a CSS viewport, record the actual values and use a clearly labeled viewport emulation only as supplemental evidence.
3. At every CSS width, repeat at DPR 1, 2, and 3 where emulation allows. Tier must be identical for a fixed CSS width. Explicitly check 1280px/DPR2 and 650px/DPR3 remain standard.
4. Check the tier boundaries 2559/2560 and 3839/3840 CSS px, plus mobile widths 320, 375, 390, 430, and 767px at DPR2/3. Mobile must retain standard tokens and have no horizontal overflow.
5. Compare `/user/login`, `/dashboard/home`, a list/search page, and one editor drawer/modal. Inspect body and title sizes, button/input heights, navigation widths, wrapping, clipping, and the existing modal/greeting/theme/model-drawer behavior. Save before/after screenshots and list any viewport that was simulated.
6. Once browser review passes, run the normal project typecheck and targeted UI checks, then record those separately from the visual evidence.
