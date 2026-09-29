# Lumira Interface Design Standard

This file is the implementation contract for Lumira page design. Ant Design's official design documentation and component references are the source of truth. Do not invent visual rules or replace an official component pattern with a custom treatment without documenting the product need and the reference that supports it.

## Official references

- [Ant Design introduction](https://ant.design/docs/spec/introduce/): purpose and scope of the design system.
- [Ant Design design values](https://ant.design/docs/spec/values/): Natural, Certain, Meaningful, Growing.
- [Ant Design layout](https://ant.design/docs/spec/layout/): canvas, responsive adaptation, 8-unit grid, 24-column raster, common scales.
- [Ant Design font](https://ant.design/docs/spec/font/): font family, scale, line height, weight, and color hierarchy.
- [Ant Design colors](https://ant.design/docs/spec/colors/): color palette and semantic color use.
- [Ant Design motion](https://ant.design/docs/spec/motion-cn/): natural, efficient, restrained motion.
- [Ant Design components](https://ant.design/components/): component behavior, APIs, tokens, and examples.
- [Ant Design ProLayout](https://procomponents.ant.design/components/layout/): official application shell, layout modes, and layout tokens.
- [Ant Design ProTable](https://procomponents.ant.design/components/table/): official table and search-form composition.
- [Ant Design QueryFilter](https://procomponents.ant.design/components/query-filter/): query-form layout, controls, and responsive behavior.
- [Ant Design Alert](https://ant.design/components/alert/): persistent messages for information that needs attention.
- [Ant Design Form](https://ant.design/components/form/): field-level help and extra guidance.
- [Ant Design Message](https://ant.design/components/message/): transient feedback after user operations.
- [Ant Design Notification](https://ant.design/components/notification/): detailed or pushed notifications.
- [Ant Design Tooltip](https://ant.design/components/tooltip-cn/): brief explanations for labels and controls.
- [Ant Design Popover](https://ant.design/components/popover-cn/): richer explanatory content or actions.
- [Ant Design X components](https://x.ant.design/components/): official AI interface components.
- [Ant Design X with Umi](https://x.ant.design/docs/react/use-with-umi/): integration reference for this frontend framework.
- [Ant Design X FAQ](https://x.ant.design/docs/react/faq/): responsive guidance for AI interfaces.

## Required design decisions

1. **Start from the task and the user role.** Organize controls around the user's work. Make the purpose, available actions, and result clear. Keep each page focused and avoid adding decorative elements that do not help complete the task.
2. **Use the project design system.** Use the installed Ant Design components and theme tokens for forms, tables, navigation, status, feedback, overlays, and responsive behavior. Use Ant Design X components for AI chat interactions. Place standalone list filters in a `ProCard` surface with `QueryFilter`, matching ProTable's official search wrapper. Do not imitate component appearance with custom markup when the documented component covers the pattern.
3. **Use a consistent layout rhythm.** Follow the official 8-unit spacing scale and 24-column grid where a grid is needed. Let the work area adapt to the viewport; do not assume one fixed canvas width. Keep the existing Lumira shell and server-owned navigation behavior. Use ProLayout's `mix` mode for the global brand and utility header above the side menu; keep the header, menu, responsive collapse behavior, and route groups inside ProLayout instead of recreating its shell in custom markup. Use ProLayout theme tokens for the header and canvas. The canvas stays flat to meet the product's no-gradient requirement (`bgLayout: null`); use the Ant Design container background token for both fixed and scrolled header states so the brand bar remains distinct from the canvas in light and dark themes. Style the selected menu item with the ProLayout sider token, using the primary blue fill and white text in dark mode while retaining the light theme's default selected treatment.
4. **Keep hierarchy and text readable.** Use the official type hierarchy and semantic text colors. Keep headings, supporting text, labels, and table content consistent with the active Ant Design theme. Do not add one-off font sizes or colors without a documented reason.
5. **Show state and feedback at the point of action.** Loading, empty, success, warning, and error states must explain what happened and what the user can do next. Destructive or consequential actions require a clear confirmation and a visible result.
6. **Choose feedback by its purpose and location.** Use `Form.Item` help or extra for guidance tied to a field. Use `Tooltip` for a short explanation of a label or control, with keyboard focus support. Use `Popover` for longer details or actions. Use `message` for transient results after a user operation and `notification` for detailed or pushed events. Use `Alert` only for persistent, currently relevant information that needs the user's attention; do not use it for routine page introductions or repeat guidance already shown elsewhere.
7. **Use motion only to clarify change.** Motion must be natural, efficient, and restrained. It must not delay task completion or distract from page content.
8. **Support the target viewport.** Use Ant Design responsive grid and breakpoint behavior. Keep controls usable on narrow screens; move secondary navigation or supporting panels into documented responsive patterns instead of shrinking the main controls until they are hard to use.

## AI interface requirements

- Build chat with the documented Ant Design X components (`Bubble`, `Sender`, `Conversations`, `Welcome`, and `Prompts` as appropriate) and keep the system shell in Ant Design Pro.
- Keep provider configuration in normal Ant Design forms and tables. Show connection state, provider type, model identifier, and declared capabilities as explicit data.
- Distinguish model output from system-confirmed results. Show proposed system actions as reviewable actions and require explicit confirmation before executing writes.
- Do not imply that a model supports tool calls, structured judging, vision, embeddings, or a provider-specific feature until the selected model has been checked for that capability.
- Display which configured provider receives a prompt. Never expose provider credentials in browser responses or rendered page state.

## Change and review rule

Before implementing a new page pattern, check the linked official guideline and the installed component version. Record any necessary deviation in the page or feature documentation with its reason. For visual review, check the real route and relevant interaction at desktop and narrow viewport sizes; do not claim system-wide conformity based only on a successful build.
