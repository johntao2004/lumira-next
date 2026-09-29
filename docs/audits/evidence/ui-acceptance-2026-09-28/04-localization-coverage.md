# 平台固定 UI 文案覆盖审计

审计日期：2026-09-29  
范围：/Users/johntao/Documents/GitHub/lumira-next 当前工作区源码。  
性质：只读静态源码审计；不是浏览器验收、运行时语言切换验收或部署证明。没有访问浏览器、构建、运行测试、重启服务或修改业务源码。

## 结论摘要

全量枚举了 apps/web/src 下非生成的 111 个 TSX 文件、15 个前端模块 manifest、33 个 dashboard route ID，并沿实际注册表、renderer、shell、共享组件和本地化运行时追踪固定 UI 文案的使用。目录中实际有 1,021 个英文源 key；这个数量本身不能证明文案覆盖完整。

本次确认 12 个仍直接显示英文固定 UI literal 的点，分布在导航表单/开关、AI 判断结果、AI 能力计划表、短信 provider 选项和反馈富文本编辑器的无障碍标签。它们尚无目录 key，当前静态 8 locale 全都无法管理。补丁草稿建议新增 12 个 key 并为 8 种语言提供文案。

现有 AI key 有 71 个 locale-cell 缺值，涉及 14 个既有 key：zh-TW 缺 9，ja 缺 6，ko/fr/es/de 各缺 14。任务最初写的 66 与源码逐项差分结果不符；草稿按可复核的 71 补齐。当前前端英文回退会掩盖缺值，但这不等于目标语言已覆盖。

另有 64 个没有静态前端引用的 orphan 候选。3 个 key 由动态错误/通知路径消费；2 个是已定位到的错误映射候选；1 个是后端 DTO 字段；其余 61 个用途仍未确认。按要求全部保留，不删除或改写。

## 注册、生成和实际消费链

- 静态源目录是 apps/web/src/platform/i18n/messages.ts；Locale 是该对象的 locale key，MessageKey 是英文 locale key 的并集类型，见 messages.ts:7886-7887。
- scripts/generate-localization-catalog.mjs 解析字面量对象并生成 builtin-messages.json。当前校验重复 locale/key、空目录、非英文源和“locale 不得有英文目录外的额外 key”，见该脚本约 139-149 行；它没有验证每个 locale 必须包含英文目录全部 key，因此缺值会通过生成。
- Maven 在 backend/pom.xml 的 generate-resources 阶段运行生成器；LocalizationBuiltinCatalog.java 从 classpath 读取 JSON，校验格式、英文 keyCount 和“没有英文目录外的 key”，但同样没有验证 key set 相等，见 LocalizationBuiltinCatalog.java:21-42。
- LocalizationService 将内置文案和启用的运行时自定义文案合并后向前端提供；bundle 与 list 的实现分别见 backend/src/main/java/com/lumira/platform/localization/LocalizationService.java 的 bundle/list 方法。前端 providers.tsx:139-162 获取 /api/public/localization/bundle/{locale}，运行时文案优先，缺少时由 messages.ts:7915-7917 回退英文再回退 key。
- 实际 route 注册由 apps/web/src/platform/modules/catalog.ts:4-46 的 15 manifests 汇总；apps/web/src/platform/modules/registry.tsx 为 route ID 建 page renderer 并校验重复/未登记页面；apps/web/src/platform/shell/navigation.tsx 生成导航；apps/web/src/platform/shell/workspace.tsx 消费 route；app.tsx、pages/dashboard/section.tsx、features/platform/module.tsx 共同处理 shell/home 的特殊渲染。
- providers.tsx:9-31,169-170 将 8 个 locale 同时映射到 Ant Design locale 包。Ant Design/ProComponents 内部默认文案由 vendor locale 负责，不由 messages.ts 目录直接管理。

## 15 manifests 与 33 个 dashboard route

下表是静态 catalog 注册数，不代表每页都在本回合完成运行时 UI 验收。用户/账户身份 manifest 不直接声明 dashboard route；其组件由 shell、登录页和账户菜单调用。

| Manifest ID | Route ID / 页面映射 |
|---|---|
| identity | 无 dashboard route；登录及账户抽屉 |
| platform | home（Workspace 特殊页）、modules（modules-page.tsx）、ai-console（ai-console-page.tsx）、ai-models（ai-models-page.tsx）、ai-judgment（ai-judgment-page.tsx）、ai-capabilities（ai-capabilities-page.tsx） |
| directory | users 与 online-users 由 UserManagementTabs/RouteTabs 渲染；roles（roles-page.tsx）；organizations（organizations-page.tsx） |
| dictionaries | dictionaries（dictionaries-page.tsx） |
| files | files（files-page.tsx）；files-all（admin-files-page.tsx） |
| settings | branding、smtp、sms、profile-fields、agreements、watermark、maintenance、security、passkey、file-retention；settings/module.tsx 将它们组装为共享 RouteTabs 子页 |
| events | event-outbox（outbox-page.tsx） |
| navigation | navigation（navigation-page.tsx） |
| notifications | notifications（admin-notifications-page.tsx） |
| monitoring | monitoring（monitoring-page.tsx） |
| audit-console | audit（audit-page.tsx） |
| feedback | feedback（feedback-page.tsx） |
| sensitive-words | sensitive-words（sensitive-words-page.tsx） |
| localization | localization（localization-page.tsx） |
| communication | mail-deliveries（mail/delivery-page.tsx）；sms-deliveries（sms/delivery-page.tsx）；二者由 RouteTabs 组合 |

合计 33 个 manifest route ID。特殊非 dashboard 页面还包括 /user/login、not-found；/dashboard/section 是通用 shell 入口而非额外 manifest route。identity 是上述 15 个 manifest 中唯一没有 dashboard route 的 manifest。

## 111 个非生成 TSX 文件清单

计数使用 rg --files apps/web/src -g '*.tsx'，默认忽略 .umi 与生成文件；结果为 111。按顶层目录是 app.tsx 1、features 69、pages 3、platform 38。扫描不把 apps/web/src/.umi、apps/web/src/.umi-production 下 6 个生成 TSX 混入产品源。

### app 与 features

apps/web/src/app.tsx

apps/web/src/features/account/avatar-editor.tsx  
apps/web/src/features/account/drafts-drawer.tsx  
apps/web/src/features/account/email-drawer.tsx  
apps/web/src/features/account/module.tsx  
apps/web/src/features/account/passkeys-drawer.tsx  
apps/web/src/features/account/password-drawer.tsx  
apps/web/src/features/account/phone-drawer.tsx  
apps/web/src/features/account/profile-completion.tsx  
apps/web/src/features/account/profile-drawer.tsx  
apps/web/src/features/account/profile-values-drawer.tsx  
apps/web/src/features/account/security-events-drawer.tsx  
apps/web/src/features/account/sessions-drawer.tsx  
apps/web/src/features/ai/ai-capabilities-page.tsx  
apps/web/src/features/ai/ai-console-page.tsx  
apps/web/src/features/ai/ai-judgment-page.tsx  
apps/web/src/features/ai/ai-models-page.tsx  
apps/web/src/features/audit/audit-page.tsx  
apps/web/src/features/audit/module.tsx  
apps/web/src/features/communication/module.tsx  
apps/web/src/features/dictionaries/dictionaries-page.tsx  
apps/web/src/features/dictionaries/dictionary-fields.tsx  
apps/web/src/features/dictionaries/dictionary-file-import.tsx  
apps/web/src/features/dictionaries/dictionary-items-page.tsx  
apps/web/src/features/dictionaries/dictionary-transfer.tsx  
apps/web/src/features/dictionaries/module.tsx  
apps/web/src/features/directory/module.tsx  
apps/web/src/features/events/module.tsx  
apps/web/src/features/events/outbox-page.tsx  
apps/web/src/features/feedback/feedback-page.tsx  
apps/web/src/features/feedback/module.tsx  
apps/web/src/features/files/admin-files-page.tsx  
apps/web/src/features/files/file-trash-actions.tsx  
apps/web/src/features/files/file-usage.tsx  
apps/web/src/features/files/files-page.tsx  
apps/web/src/features/files/module.tsx  
apps/web/src/features/localization/localization-page.tsx  
apps/web/src/features/localization/module.tsx  
apps/web/src/features/mail/delivery-page.tsx  
apps/web/src/features/modules/module-history-drawer.tsx  
apps/web/src/features/modules/module-packages-drawer.tsx  
apps/web/src/features/modules/modules-page.tsx  
apps/web/src/features/monitoring/module.tsx  
apps/web/src/features/monitoring/monitoring-page.tsx  
apps/web/src/features/navigation/module.tsx  
apps/web/src/features/navigation/navigation-page.tsx  
apps/web/src/features/notifications/admin-notifications-page.tsx  
apps/web/src/features/notifications/module.tsx  
apps/web/src/features/notifications/notifications-button.tsx  
apps/web/src/features/organizations/organization-form.tsx  
apps/web/src/features/organizations/organizations-page.tsx  
apps/web/src/features/platform/module.tsx  
apps/web/src/features/roles/roles-page.tsx  
apps/web/src/features/sensitive/module.tsx  
apps/web/src/features/sensitive/sensitive-words-page.tsx  
apps/web/src/features/settings/agreements-page.tsx  
apps/web/src/features/settings/branding-page.tsx  
apps/web/src/features/settings/file-retention-page.tsx  
apps/web/src/features/settings/maintenance-page.tsx  
apps/web/src/features/settings/module.tsx  
apps/web/src/features/settings/passkey-page.tsx  
apps/web/src/features/settings/profile-fields-page.tsx  
apps/web/src/features/settings/security-page.tsx  
apps/web/src/features/settings/sms-page.tsx  
apps/web/src/features/settings/smtp-page.tsx  
apps/web/src/features/settings/watermark-page.tsx  
apps/web/src/features/sms/delivery-page.tsx  
apps/web/src/features/users/online-users-page.tsx  
apps/web/src/features/users/user-editor.tsx  
apps/web/src/features/users/users-page.tsx

### pages 与 platform

apps/web/src/pages/dashboard/section.tsx  
apps/web/src/pages/not-found.tsx  
apps/web/src/pages/user/login.tsx  
apps/web/src/platform/auth/agreement-links.tsx  
apps/web/src/platform/auth/login-page.tsx  
apps/web/src/platform/auth/maintenance-notice.tsx  
apps/web/src/platform/auth/password-recovery-drawer.tsx  
apps/web/src/platform/branding/footer.tsx  
apps/web/src/platform/branding/provider.tsx  
apps/web/src/platform/branding/watermark.tsx  
apps/web/src/platform/components/account-avatar.tsx  
apps/web/src/platform/components/async-button.tsx  
apps/web/src/platform/components/capacity-meter.tsx  
apps/web/src/platform/components/delete-action.tsx  
apps/web/src/platform/components/dictionary-select.tsx  
apps/web/src/platform/components/ellipsis-text.tsx  
apps/web/src/platform/components/feedback-bubble.tsx  
apps/web/src/platform/components/form-drawer.tsx  
apps/web/src/platform/components/hierarchy-table-label.tsx  
apps/web/src/platform/components/markdown-content.tsx  
apps/web/src/platform/components/new-password-fields.tsx  
apps/web/src/platform/components/platform-drawer.tsx  
apps/web/src/platform/components/platform-form.tsx  
apps/web/src/platform/components/query-failure.tsx  
apps/web/src/platform/components/resource-picker.tsx  
apps/web/src/platform/components/resource-select.tsx  
apps/web/src/platform/components/resource-table.tsx  
apps/web/src/platform/components/rich-text-editor.tsx  
apps/web/src/platform/components/route-tabs.tsx  
apps/web/src/platform/components/settings-section.tsx  
apps/web/src/platform/components/table-actions.tsx  
apps/web/src/platform/drafts/draft-actions.tsx  
apps/web/src/platform/i18n/language-switcher.tsx  
apps/web/src/platform/modules/registry.tsx  
apps/web/src/platform/providers.tsx  
apps/web/src/platform/shell/account-menu.tsx  
apps/web/src/platform/shell/app-error-boundary.tsx  
apps/web/src/platform/shell/dynamic-page.tsx  
apps/web/src/platform/shell/navigation.tsx  
apps/web/src/platform/shell/page-component-registry.tsx  
apps/web/src/platform/shell/workspace.tsx

## 13 个固定 UI 文案域的覆盖结果

静态扫描覆盖 111 TSX 文件，并按实际 manifest/renderer 调用路径检查下列 13 域。表内“覆盖”只表示源码目录/消费路径审计，不代表当前运行浏览器验收。

| 文案域 | 扫描面 | 结果 |
|---|---|---|
| 1. 菜单与路由标题 | 15 manifests、catalog、registry、shell navigation、workspace | route label key 和静态导航标题通过目录消费；数据库 labelDefault 是管理员可编辑菜单数据。navigation 表单 sectionKey 字段标签是遗漏。 |
| 2. 页面标题、卡片标题 | 33 route renderer、全部 feature page | 使用 t() 的标题均纳入目录；未再确认出固定标题 literal 遗漏。AI 能力表中的 3 个固定 cell 是例外。 |
| 3. 按钮及操作 | TableActions、AsyncButton、DeleteAction、页面操作列 | 通用操作标签大多由 t() 提供；未再确认独立的固定按钮文案遗漏。navigation Switch 的 global/local 是固定子标签遗漏。 |
| 4. 表单字段标签 | 全部页面 Form.Item 与共享 PlatformForm/FormDrawer | 目录标签覆盖为主；navigation 的裸 sectionKey 标签遗漏。 |
| 5. 表单 placeholder | 页面 Input、Select、TextArea、共享 ResourceSelect | 固定提示通过 t()；URI/样例/字段元数据 placeholder 按技术样例或可配置数据分类，不视为固定文案缺 key。 |
| 6. 必填/校验 | 表单 rules、页面验证器、错误映射 | 自定义校验路径有目录 key；没有 message 的 Ant Design required 规则由 Form locale 提供，不属于 messages.ts 自管的应用文案。 |
| 7. help、hint、说明段落 | HelpLabel、Typography、Alert、Form extra | 业务说明由 t() 为主；管理员填写的标题/内容和菜单 labelDefault 属可配置数据。AI judgment initialValues 是可编辑演示样例。 |
| 8. 表格列标题/单元格 | ResourceTable 与 33 routes 中表格 | 固定列标题为目录 key；状态/动态 API 值按 enum mapping 或数据展示。AI 能力 planned rows 的 3 个固定英文单元格遗漏。 |
| 9. 空态、加载与查询失败 | ResourceTable、QueryFailure、Empty、Spin | 应用层空态/失败标题通过 t() 或共享组件；Ant Design 自身 locale 文案留在 vendor locale 边界。invalidAiChat 与 invalidLocalizationImport 的错误映射见后文。 |
| 10. 固定状态与 provider 显示名 | AI provider options、settings enum、notification | 固定 provider 表示 key 或第三方专名；SMS 选项 HTTPS JSON Webhook 是未登记的固定 UI 文案。AI provider 的部分现存语言值缺失。 |
| 11. Dialog、drawer、modal | FormDrawer、PlatformDrawer、DeleteAction 与功能 drawers | 标题、动作和确认文案以目录 key 为主；组件继承 Ant Design dialog 的原生默认文案不重复登记。 |
| 12. Toast、后端错误、通知 | messageKeyFor、各 feature allowlist、NotificationsButton | 固定提示通过目录 key；用户提交的反馈文字/通知 title/body 是内容。动态通知 key 由 isMessageKey 消费；两项错误 key 路由存在遗漏候选。 |
| 13. 无障碍标签 | aria-label、role、icon-only buttons | 发现富文本编辑器 4 个英文 aria-label 未调用 t()；它们对屏幕阅读器可见，应纳入语言管理。 |

## 共享组件与真实调用清单

调用点根据 JSX tag 源码计数；下列数字是静态调用点/包含调用的 TSX 文件数，不等于页面数。共享组件本身也属于 111 个源文件扫描面。

| 共享组件 | 调用点 / 文件数 | 调用文件或页面家族 |
|---|---:|---|
| ResourceTable | 28 / 25 | resource-picker；organizations、sms/mail delivery、navigation、roles、feedback、audit、online-users、users/user-editor、sensitive、files/admin-files、dictionary-items/dictionaries、account sessions/drafts/security-events、event outbox、modules/history/packages、notifications、localization |
| FormDrawer | 19 / 16 | organizations、navigation、roles、feedback（2）、user-editor、sensitive（2）、dictionary file/items/dictionaries/transfer、account password/profile/profile-values、modules、module-packages（2）、admin notifications |
| SettingsSection | 10 / 10 | security、watermark、passkey、branding、agreements、smtp、sms、profile-fields、maintenance、file-retention |
| RouteTabs | 5 / 3 | directory/module、settings/module（3 组）、communication/module |
| DeleteAction | 16 / 15 | organizations、navigation、roles、online-users、users、sensitive、file-trash/files/admin-files、dictionary items/dictionaries、account sessions（2）/avatar/drafts、localization |
| QueryFailure | 23 / 17 | agreement-links、maintenance notice、SettingsSection、ResourceSelect、roles、AI models/judgment/capabilities、user-editor（4）、file usage、dictionary items、account phone/avatar（2）/passkeys/profile completion/email、monitoring |
| FeedbackBubble | 31 / 21 | login、password recovery、draft actions、SettingsSection（3）、ResourceTable、organizations、roles、feedback（3）、user-editor、sensitive（2）、dictionary import/items/dictionaries（2）/transfer、account phone（2）/password/avatar/passkeys/profile values/profile/email（2） |
| AsyncButton | 23 / 15 | login、password recovery（2）、draft actions、SettingsSection（2）、ResourceSelect、FormDrawer、sensitive（2）、files、dictionary import/transfer、account phone（2）/avatar/email（2）、monitoring、localization（4） |
| TableActions | 21 / 19 | organizations、navigation、roles、feedback、AI models/capabilities、online-users、users、sensitive、files（2）、admin-files（2）、dictionary items/dictionaries、account sessions/drafts、event outbox、modules/module packages、localization |
| PlatformDrawer | 11 / 11 | password recovery、feedback、AI models、account phone/sessions/drafts/passkeys/security-events/email、module history/packages |
| 其他共享调用 |  | ResourceSelect（3：dictionary-select、organization-form、dictionary-items）；DictionarySelect（1：profile-values-drawer）；RichTextEditor（1：feedback-page）；ResourcePicker（1：maintenance-page）；CapacityMeter（4：user-editor/files file-usage）；EllipsisText（7：navigation/audit）；HierarchyTableLabel（1：organizations）；MarkdownContent（1：agreement-links）；DraftActions（1：feedback-page）；LanguageSwitcher（2：login/workspace）。 |

ResourceTable 的核心封装在 platform/components/resource-table.tsx，内部统一错误提示/表格空态/搜索与分页；SettingsSection 统一 10 个管理设置页的读取、保存、错误、恢复和历史；RouteTabs 将 manifest route 拆分到设置、目录和通信的共享 tab shell。共享消费路径已纳入前述 13 域扫描，不能把一个组件 key 命中误算成每个页面分别经过浏览器验收。

## 确认的 12 个未注册固定 UI literals

以下是本次已确认而非 orphan 推测的遗漏。路径和行号为当前源版本；这些 literal 位于实际 route/共享组件渲染路径。提出的 message key 是 draft 命名，不是已写入源目录。

| Route / 组件 | 文件:行 | 固定 literal | 建议 key |
|---|---|---|---|
| /dashboard/navigation | apps/web/src/features/navigation/navigation-page.tsx:323 | sectionKey（Form.Item label） | navigationSectionKey |
| /dashboard/navigation | apps/web/src/features/navigation/navigation-page.tsx:332 | global（Switch checkedChildren） | navigationGlobal |
| /dashboard/navigation | apps/web/src/features/navigation/navigation-page.tsx:332 | local（Switch unCheckedChildren） | navigationLocal |
| /dashboard/ai-judgment | apps/web/src/features/ai/ai-judgment-page.tsx:85 | Tokens（结果 usage label） | aiTokenUsage |
| /dashboard/ai-capabilities | apps/web/src/features/ai/ai-capabilities-page.tsx:25 | module specific（planned operation cell） | aiCapabilityModuleSpecific |
| /dashboard/ai-capabilities | apps/web/src/features/ai/ai-capabilities-page.tsx:25 | module permissions（planned permission cell） | aiCapabilityModulePermissions |
| /dashboard/ai-capabilities | apps/web/src/features/ai/ai-capabilities-page.tsx:26 | module specific（planned operation cell） | aiCapabilityModuleSpecific |
| /dashboard/ai-capabilities | apps/web/src/features/ai/ai-capabilities-page.tsx:26 | settings permissions（planned permission cell） | aiCapabilitySettingsPermissions |
| /dashboard/sms | apps/web/src/features/settings/sms-page.tsx:47 | HTTPS JSON Webhook（Select option label） | smsProviderWebhook |
| feedback rich-text shared consumer | apps/web/src/platform/components/rich-text-editor.tsx:211 | Bold（aria-label） | editorBold |
| feedback rich-text shared consumer | apps/web/src/platform/components/rich-text-editor.tsx:219 | Italic（aria-label） | editorItalic |
| feedback rich-text shared consumer | apps/web/src/platform/components/rich-text-editor.tsx:227 | Unordered list（aria-label） | editorUnorderedList |
| feedback rich-text shared consumer | apps/web/src/platform/components/rich-text-editor.tsx:235 | Ordered list（aria-label） | editorOrderedList |

上表有 13 次显示/属性位置，但只有 12 个唯一 literal/key：module specific 在两行复用同一文案。这也是为什么按 literal occurrence 与 catalog key 数计数会得到不同数字。所有 rich-text toolbar 按钮由 feedback-page.tsx 的唯一当前消费者渲染；四个 aria-label 不是屏幕可见按钮文本，但属于用户可访问的固定界面名称，应可翻译。SMS 的 Webhook 是既有 provider enum 的可见名称，不建议把第三方/协议名误分类为用户输入。

## 既有 AI key 的 locale 差分

当前 messages.ts 的静态 key 集差分如下。统计是每个 locale 中缺少的英文源 key 数；缺失值数为 71 个 locale-cell，不是 71 个独立 key。缺值在前端 translate 中回退英文，所以普通使用可能不会显示原始 key，但语言包仍不完整。

| Locale | 当前 key 数 | 缺值数 | 缺少 key |
|---|---:|---:|---|
| zh-CN | 1,021 | 0 | — |
| zh-TW | 1,012 | 9 | aiCapabilitiesOverview；aiKindCustomOpenAi；aiKindDeepSeek；aiKindMoonshot；aiKindOpenAi；aiKindOpenRouter；aiKindQwen；aiKindSiliconFlow；aiKindZhipu |
| en | 1,021 | 0 | —（源 locale） |
| ja | 1,015 | 6 | aiCapabilitiesOverview；aiCloudCompatibleHint；aiCustomBaseUrlPlaceholder；aiDiscoverModels；aiModelDiscoverySuccess；aiModelNamePlaceholder |
| ko | 1,007 | 14 | aiCapabilitiesOverview；aiCloudCompatibleHint；aiCustomBaseUrlPlaceholder；aiDiscoverModels；aiKindCustomOpenAi；aiKindDeepSeek；aiKindMoonshot；aiKindOpenAi；aiKindOpenRouter；aiKindQwen；aiKindSiliconFlow；aiKindZhipu；aiModelDiscoverySuccess；aiModelNamePlaceholder |
| fr | 1,007 | 14 | 与 ko 相同的 14 个 key |
| es | 1,007 | 14 | 与 ko 相同的 14 个 key |
| de | 1,007 | 14 | 与 ko 相同的 14 个 key |

缺失 key 当前用于以下页面/消费点：

- aiCapabilitiesOverview：/dashboard/ai-capabilities 的 Alert。
- aiCloudCompatibleHint、aiCustomBaseUrlPlaceholder、aiDiscoverModels、aiModelDiscoverySuccess、aiModelNamePlaceholder：/dashboard/ai-models 的云端 provider 表单/模型列表发现控件。
- 八个 aiKind*：/dashboard/ai-models 的 provider kind 选项；英文 key 的源值为已有 provider label/品牌名（OpenAI、DeepSeek、Qwen、Zhipu GLM、SiliconFlow、OpenRouter、Moonshot Kimi、OpenAI-compatible API）。

另有 5 个 zh-TW key 的当前值与英文源 locale 相同，不计入 71 个缺值：aiCloudCompatibleHint、aiCustomBaseUrlPlaceholder、aiDiscoverModels、aiModelDiscoverySuccess、aiModelNamePlaceholder。前 4 个自然语言值是确认的翻译缺陷；aiCustomBaseUrlPlaceholder 是 URL 样例，本身不是英语文案，但草稿将其改成「例如：https://api.example.com/v1」，在保留可用 URL 样例的同时本地化提示词。key-set 完整只能证明每个 key 有值，不能证明每个值已翻译。

| zh-TW key | 当前源值 | draft 值/处理 |
|---|---|---|
| aiCloudCompatibleHint | Cloud presets use an OpenAI-compatible API. The default URL is editable. | 雲端預設使用 OpenAI 相容 API；預設網址可修改。 |
| aiCustomBaseUrlPlaceholder | https://api.example.com/v1 | 例如：https://api.example.com/v1（URL 保持原样，仅本地化示例提示） |
| aiDiscoverModels | Fetch model list | 取得模型清單 |
| aiModelDiscoverySuccess | Found {count} models. Select one or enter a model identifier. | 已取得 {count} 個模型；請選擇模型，或直接輸入模型識別碼。 |
| aiModelNamePlaceholder | Fetch the list to choose a model, or enter an identifier | 取得清單後選擇，或直接輸入模型識別碼 |

任务文字所写“补齐 66 个 locale 缺值”低于本次实际 key set 差分 5 项。推荐草稿补齐全部 71 个现有 key 缺值，使八个静态 locale 与英文 key set 相等，并更新上述 5 个 zh-TW 值；没有新增 14 个 AI key，也没有改变已存在源 locale 文案。

## 64 个 orphan 候选与动态消费

该组 key 在本次扫描到的 TSX 中没有静态 t(key) 引用，先按 key 名和服务端调用做候选分类。除下面已识别路径外，不凭命名推断而删除。

### 64 个候选完整列表

aiCapabilitiesHint  
aiCapabilityChat  
aiChatSend  
aiOverview  
aiProvider  
allFeedbackPriorities  
allFeedbackStatuses  
allLocalizationStatuses  
allOrganizations  
allSensitiveWordStatuses  
checkingDataset  
closeMenu  
closeNavigation  
copyright  
eventSearch  
feedbackHint  
fileQuotaAllocationHint  
fileQuotaBelowUsage  
fileQuotaDefaultLimits  
fileQuotaFileCountLimit  
fileQuotaInheritHint  
fileQuotaInherited  
fileQuotaStorageLimit  
invalidAiChat  
invalidLocalizationImport  
liveRefresh  
localizationConflictPolicy  
localizationDisplayName  
localizationHint  
localizationImportPayload  
localizationImportPlaceholder  
localizationImportSummary  
localizationPublished  
localizationReleaseHint  
localizationTranslation  
localizationTranslationHint  
localizationTranslations  
localizationTranslationsPlaceholder  
mailAllStates  
mailSearch  
measuredAt  
noUsers  
openMenu  
openNavigation  
packagePendingHint  
packageReadyHint  
permissionRead  
publishLocalization  
returnPortal  
rootOrganizations  
searchAudit  
searchDrafts  
searchFeedback  
searchLocalization  
searchOnlineUsers  
searchSensitiveWords  
securitySaved  
sensitiveImportCsvInvalidText  
sensitiveWordStatus  
sensitiveWordsHint  
sessionExpired  
smsAllStates  
smsSearch  
viewModules

### 活跃动态消费，已从 64 候选外单独识别

- aiDecisionProviderRequired 与 invalidAiJudgment：AI Judgment 页面用 isMessageKey(error.message) 在运行时检查服务端错误名，再调用 t(key)。引用点在 apps/web/src/features/ai/ai-judgment-page.tsx:7,49；对应后端代码在 backend/src/main/java/com/lumira/platform/ai/AiDecisionService.java。它们不是 orphan。
- notificationFeedbackSubmitted：NotificationsButton 对 API 返回的 titleKey/bodyKey 调用 isMessageKey 再翻译；后端 NotificationService 发布该 key，见 apps/web/src/features/notifications/notifications-button.tsx:14,30-33 与 backend/src/main/java/com/lumira/platform/notifications/NotificationService.java:44。它不是 orphan。
  
这三项说明仅 grep 静态 t("literal") 会误报动态 key；报告采用动态消费源码复核，不将它们算进 64。

### 已定位但可能是错误映射的候选

- invalidAiChat：AI 后端 AiController/AiAssistantService 会返回此错误 key；/dashboard/ai-console 的 catch 只把 aiProviderCredentialRequired 单独显示，其余均改成 aiProviderUnavailable，见 apps/web/src/features/ai/ai-console-page.tsx:111-123 和 backend/src/main/java/com/lumira/platform/ai/AiController.java:72、AiAssistantService.java:189。catalog 中 invalidAiChat 有文案，但实际失败不会显示它；这是高置信错误映射遗漏候选，仍应由产品决定是否要区分“输入格式无效”。
- invalidLocalizationImport：后端 LocalizationController/LocalizationService 使用此错误码；/dashboard/localization 的 errorKeys allowlist 不包含它，messageKeyFor 因而退回 unavailable，见 apps/web/src/features/localization/localization-page.tsx:26,146,181,195,205,245,261,277,315,358。此处 key 已存在目录，但前端消费映射不匹配；属于错误映射候选。
- measuredAt：后端监控 DTO 有此属性且 catalog 有其 label，但前端使用时间格式化/监控数据的方式不以这个 message key 显示表头，见 backend/src/main/java/com/lumira/platform/monitoring/MonitoringRepository.java 与 MonitoringService.java。当前按非 UI/DTO orphan 候选处理，不视为确认固定 UI 文案。

除以上 3 项外，剩余 61 个候选目前用途未确认。可能属于旧版引用、未来功能/遗留种子、后端消息码、动态间接消费或真实孤立 key；需对对应数据/API/历史设计进一步核查后才能定性，故保留。64 候选名单不等于要删除 64 个 key。

## 可配置数据、样例和平台边界

以下内容与固定 UI 文案区分，不借审计之名新增目录 key：

- /dashboard/ai-judgment 的 initialValues 在 ai-judgment-page.tsx:62 提供合成工单、问题、选项和评分等级示例。它们会进入可编辑表单，不是静态 label/按钮/helper copy；目前不作为本次 12 个固定 UI gap。
- profile-fields-page.tsx:172 的 Basic information 是创建头像字段时的默认 groupLabel。该值随后作为用户档案字段分组元数据由管理员管理，属于 configurable metadata，不作为翻译目录 key。
- navigation 的 labelDefault、profile field labels/descriptions/options、dictionary entries、用户/反馈名称、通知 title/body 是数据库或用户业务数据。运行时 navigation 会在 labelKey 是 catalog key 时翻译，否则显示 labelDefault；见 platform/shell/navigation.tsx 与平台导航配置读取。
- maintenance title/message、agreements Markdown、branding 的站点名/logo/footer/ICP 等由 Settings/公开配置 API 提供，不是固定源码 UI copy。页面字段标签与编辑/保存控件仍在固定文案审计范围。
- Dictionary 名称及项标签来自业务词典条目；“导入 JSON”“保存”等 editor chrome 才属于固定文案。
- RichTextEditor 的 Insert image、Uploading image…、Image upload failed 是组件默认 prop fallback（rich-text-editor.tsx:84-92）。当前唯一消费者 feedback-page.tsx 显式传入已本地化的对应 props，因此当前 route 上它们不是活跃遗漏；未来新增调用若省略 props 才会暴露英文 fallback。
- /dashboard/navigation 的 /dashboard/example placeholder 是 path 输入样例；module:feature:read、+8613800138000、HTTP 状态码、STARTTLS/TLS、TXT / Excel、产品/服务商名等按技术标识、数据或品牌名分类，不把它们改写成普通 UI prose。
- Ant Design/ProComponents 的默认验证文本、日期/分页/弹层文案由 providers.tsx 为八个 locale 安装的 antd/locale 包提供；由浏览器提供的 date、datetime-local 和 file picker 交互文案属于原生控件/操作系统边界。它们不由 messages.ts 直接维护，也不计入 app-owned 固定文案 key 差分。

## 978、1,021、1,018 计数口径

| 数字 | 可核实定义 | 边界 |
|---:|---|---|
| 978 | 本次初步行首属性正则计数结果；它漏掉同一行逗号分隔的 43 个属性，不能作为 catalog keyCount。 | 是审计过程中的不完整解析结果，不是当前静态 key 集规模。 |
| 1,021 | 当前工作区 messages.ts 英文源 locale 的实际唯一 key 数；用 scripts/generate-localization-catalog.mjs 同款逐属性读取方式解析，同一行多个属性也分别计入。zh-CN 同样有 1,021 个 key。 | 正确的当前静态 source keyCount。仍不说明固定 UI 完整覆盖。 |
| 1,018 | 03-ops-settings-locale.md 记录的先前运行 UI catalog count 与导出语言包 effectiveTextCount；其历史运行定义按 LocalizationService 合并的内置 key 与启用运行时自定义文案计数。历史同一套生成资源的静态 keyCount 为 984。 | 是旧运行产物/当时 runtime effective text 数，不是当前 messages.ts source key 数，也不意味着 1,018 个固定 UI literal 已逐页消费。 |

据 03-ops-settings-locale.md:39，曾运行的 JAR 内嵌 builtin-messages.json 与当时 generated resource 一致，静态 keyCount 984；同一 evidence 的 :202、:213-214 记录 UI 导出 effectiveTextCount/目录页面 1,018。该历史 UI 结果与本次当前源码解析数 1,021 分层记录，不能合并成一个“覆盖率”。

## 最小整改建议与草稿范围

1. 将上述 12 个唯一固定 UI literals 改为 t() 消费；key 新增在 en 并为八个 locale 提供值。AI capability values 在 planned rows 上按 key 翻译，API 机器码/权限码保留原值。
2. 给既有 14 个 AI key 补 71 个 locale-cell，并修复 5 个 zh-TW 当前等同英文源值的项（4 条语言文案和 1 个带本地化提示词的 URL 样例）；不新增额外 AI keys、不改源文案。
3. 在 scripts/generate-localization-catalog.mjs 对每 locale 比较完整 key set；在 LocalizationBuiltinCatalog.java 要求每个 locale 与英文 key set 相等，而不仅是“没有额外 key”。
4. 不删除 64 orphan candidates，不把用户输入/可编辑样例/元数据/第三方默认值伪装成平台固定 UI 文案。

上述实现建议在本审计初稿中仅写在 .runtime/localization-fixed-ui-gaps.patch；初稿阶段没有运行生成器或改运行源码。其后的整合与验收更新如下。

## 后续整改与 UI 证据状态 — 2026-09-29

- 已将固定文案 key 与八 locale 文案、71 个 locale-cell 补齐、zh-TW 项目修正，以及 navigation、AI judgment/capabilities、SMS provider 和 RichTextEditor 的 t() 消费修复合入当前工作区；catalog generator 现检查缺失键，`LocalizationBuiltinCatalog` 要求 locale key set 与英文源完全一致。Maven generator 实际输出 **1,033 keys for 8 static locales**；这是当前源码目录计数，与历史 runtime 的 984 / 1,018 口径仍分开。
- 运行验证：`pnpm exec tsc --noEmit` 通过；Maven `package -DskipTests`（JDK 25）通过，日志明确显示 Surefire skipped tests。Localized built-in catalog 由这次 package 生成。之前获授权并单独执行的 9 个 targeted localization service/repository tests 报告通过，但不代表 PostgreSQL UI restore integration。
- 有限真实 UI 验证见 [03-ops-settings-locale.md](03-ops-settings-locale.md)：`/dashboard/ai-capabilities` 在日语下显示 `モジュール固有 / モジュール権限`，切回简中显示 `模块专用 / 模块权限`；`sensitiveImportCsvChooseFile` / `zh-CN` 通过 UI 临时编辑后在敏感词 CSV chooser 消费者与刷新中保持新值。第一轮 Restore default 已停在精确确认框，尚未确认；第二轮尚未开始。上述 UI 覆盖仅为选定消费者，不等于 33 个 route 或八种语言的完整浏览器验收。
- 64 orphan candidates 按原审计边界全部保留，未因 key-set 修复被删除；新增 key/keyset 一致也不自动证明翻译质量或每个语言消费者都正确。

## UI 导出包结构复核 — 2026-09-29

- Edge 导出的实际文件 `/Users/johntao/Downloads/zh-CN.language-bundle (3).json`，大小 57,413 bytes，修改时间 2026-09-29 05:22:14 +08:00。文件为有效 JSON，`formatVersion=1`、`locale=zh-CN`、`effectiveTextCount=1033`，`messages` 含 1,033 个字符串值，无重复 JSON key。
- 与当前 `backend/target/generated-resources/localization/builtin-messages.json` 中 zh-CN 当前 1,033-key 目录比较：缺失 0、意外 key 0、值匹配 1,032、值差异 1；差异 key 是 `sensitiveImportCsvChooseFile`。只读布尔比较确认导出值等于当前明确保留的管理员覆盖 `QA恢复验证一 选择 CSV 文件`。当前生成目录另外与 `messages.ts` 的 1,033 个 zh-CN 静态值逐项一致。
- 这份完整导出包反映运行时管理员覆盖，不能当作当前静态 zh-CN 目录的逐值原样副本；本复核没有导入、改写、删除或触碰其内容。有效合成导入与真实 UI consumer/刷新持久化仍在进行中。
