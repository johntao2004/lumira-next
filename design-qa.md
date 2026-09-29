# Standard Ant Design Pro sidebar QA

## Scope

- The experimental Art Design Pro X dual-rail, compact-label, pin/auto-hide, and tree/dual switching treatment was removed at the user's request.
- Navigation remains permission-filtered and server-ordered, but rendering is delegated to the standard Ant Design Pro `ProLayout` sidebar.
- Desktop defaults to Ant Design Pro's 64px compact state and expands to a restrained 208px tree only on request.
- Mobile uses a 208px `ProLayout` navigation drawer.

## States and interactions checked

- The current route's top-level group opens automatically and the active page is selected.
- Other groups can be expanded and real route links navigate normally.
- The built-in desktop collapse control switches between 64px and 208px.
- The long administration menu keeps wheel and keyboard scrolling without rendering a second scrollbar or reserving its previous 15px gutter.
- The retired mode, label-density, pin, flyout, and custom tree controls are absent.
- The sidebar remains fixed while a real settings page scrolls.
- Mobile drawer behavior, light/dark themes, eight locales, and 320–1440px responsive widths remain covered.
- Theme and locale continue to persist; retired navigation preferences are ignored.

## Visual evidence

- Desktop standard sidebar: `.runtime/browser-checks/ant-pro-final/navigation-standard-1440-light.png`
- Focused 208px desktop crop: `.runtime/browser-checks/sidebar-reference/implementation-sidebar-standard.png`
- Mobile drawer: `.runtime/browser-checks/ant-pro-final/navigation-drawer-390-light.png`
- Responsive shell: `.runtime/browser-checks/pro-layout-desktop.png` and `.runtime/browser-checks/pro-layout-mobile.png`

## Acceptance

- Production build and TypeScript must pass.
- Unit/architecture and browser suites must pass after the sidebar removal.
- Visual QA must report no page errors, console errors, duplicate titles, or horizontal overflow.
