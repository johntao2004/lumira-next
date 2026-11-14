# React 依赖告警审查记录

审查基准：`a6a84a4`，2026-10-19。对正式门禁报告的全部26条依赖诊断逐项核对源码；同一effect可能有两条诊断，不代表两个缺陷。

本轮修复权限搜索树的完整展开签名并增加回归测试；偏好迁移加入已稳定的updatePreferences依赖。剩余21条保持可见，其中静态词典版本是必要的主动失效信号，认证流程保留原有代际与取消护栏。没有为消警告批量修改认证行为。

下面行号对应审查基准，路径默认相对`apps/web/src`；插件项另列完整路径。判断来自源码审查与已有自动化，尚未进行真实浏览器验收。

| # | 文件:行 / 警告 | 判断与最小处理 | 回归重点 |
| --- | --- | --- | --- |
| 1 | `features/ai/ai-console-page.tsx:218`，setModelName | **历史，未证实缺陷。** 当前 effect 同步执行，已有依赖覆盖模型合法性判断；setter 捕获 activeSessionKey。可在 effect 内直接使用函数式 setHistory，仅修改 activeSessionKey 对应会话，并显式增加该 key。不能直接把 setModelName 写入依赖：其 const 声明在 effect 之后，会触发 TDZ。 | 无效模型只修正活动会话；切换同模型的会话不写错历史。 |
| 2 | `features/files/admin-files-page.tsx:92`，filesBatch/trashBatch | **历史，有效 tab-only 语义。** batch 对象每次 render 新建，reset 只读稳定 busy ref 和 state setter。不要加入整个对象。可先在 `use-file-batch-actions.tsx` 把 reset 改成 useCallback([])，解构两个 reset 后依赖 tab/reset。 | 普通 render 保留选择；切换 tab 清除一次；忙碌时不清除。 |
| 3 | `features/files/files-page.tsx:45`，同上 | 与 #2 同一生产者原因及修法。直接补对象会因 reset 写入新 [] 造成重复 render。 | 与 #2 相同，覆盖个人文件页。 |
| 4 | `features/monitoring/api-docs-page.tsx:48`，spec | **历史，有效 mount-only 语义。** Swagger 只初始化一次；后续 `[spec, system]` effect 已负责 updateSpec。可把构造参数写成 `spec: lastSpec.current` 表达初始快照，不能加入 spec 重建整个系统。 | spec 更新只调用 updateSpec，不重建 Swagger、不重置用户展开状态。 |
| 5 | `features/navigation/navigation-page.tsx:158`，editor | **历史，标量身份足够。** memo 只用 editor 是否存在及 id。先提取 editorId，函数体只读 editorId，依赖 `[editorId, query.data]`。 | 改编辑目标/树邻接关系后，禁止父节点集合正确；同 ID 改展示字段不额外重算。 |
| 6 | `features/roles/role-permission-picker.tsx:296`，allPages/groups.length/treeData/value | **历史，有效一次性展开。** initializedExpandedKeys 防止用户勾选或后台刷新重新打开手动折叠节点；父层按角色身份重挂载。保留该 guard。可将 permissionMap/allPages 派生移入 effect，另传稳定 roots signature，依赖 groups/value/roots signature；不能删除一次性 guard。 | 目录延迟到达后初始化一次；勾选权限、手动折叠后不重开；切角色重新初始化。 |
| 7 | `features/roles/role-permission-picker.tsx:316`，searchResults/visiblePageRows | **历史，确认签名不完整。** 旧签名省略 group.source/page.navigationGroup，展开 key 却依赖两者。提取纯函数输出实际全部展开 key，序列化后供 effect 使用。不能直接补每次新建数组，因为 effect 每次写新 expandedKeys。 | id/path 不变但导航组/source 改变时展开新分支；只改标签不重开已折叠分支。 |
| 8 | 同文件:316，复杂依赖表达式 | 与 #7 同一底层问题；命名完整 signature，effect 内解析，依赖 `[searchText, signature]`。本轮已修复 #7/#8。 | unmapped-only、重复页去重，以及完整 key 签名稳定性。 |
| 9 | `features/settings/branding-page.tsx:77`，field | **历史，当前调用身份稳定。** 图像控件由 `Form.Item key={field}` 承载，换 field 会重挂载；无需认定当前清理错字段。补 `[field]` 对现调用安全。 | 上传中卸载清除正确字段 pending，回收 URL，拒绝迟到完成。 |
| 10 | `features/users/user-editor.tsx:94`，setSaveError | **历史，生产者不稳定。** `useSubmitAction.setError` 每次重建，但只访问 React setter；不能直接补依赖，否则每次 render 都可能重置编辑表单。先在生产者用 useCallback([]) 稳定 setError，再显式补 setSaveError。 | 编辑输入不重置；开关编辑器仍清旧错误；capacity 重试状态仍正确。 |
| 11 | `features/users/user-editor.tsx:116`，detail.data | **历史风险，本轮已绑定显示快照并增加状态回归。** 按 id 填表避免背景 refetch 覆盖草稿；然而 savePlan/expected version 当前读最新 detail.data。应固定打开时 baseline/version，或遇到新版本阻止提交并要求显式刷新。不能简单加 detail.data。详见下文风险。 | 同 ID 后台 V1→V2，不丢输入、不把旧值当 V2 修改提交；显式 retrySnapshots 才重新基线化。 |
| 12 | `features/users/user-editor.tsx:121`，profileValues.data | **历史，版本化快照触发有依据。** 当前按 version/schemaVersion 重填字段；users-page:81 按用户 ID 给 UserEditor key，跨用户必重挂载。可将“snapshot 按 sessionId/version/schemaVersion”的意图显式化，但不要以此宣称发现当前跨用户漏洞。新版后台快照覆盖未提交扩展字段也应纳入 #11 回归。 | keyed 换用户时同版本也正确；同版本 refetch 不冲掉草稿；显式刷新/版本变化按既定编辑策略处理。 |
| 13 | `features/users/user-editor.tsx:145`，selectedRoleIds fallback 新数组 | **历史，性能/引用稳定性。** 缺少数据时 `?? []` 每次新建，使排序 memo 失效；没有证明请求失控，query key 按内容识别。用模块级只读 EMPTY_ROLE_IDS，或把选择与排序一起放进依赖 roleSelection/roles.data?.roleIds 的 memo。 | 无角色时 render 不重算/多发请求；排序不修改原数组。 |
| 14 | `platform/auth/captcha-panel.tsx:101`，start | **认证历史，有效 context-scoped 调度，不应盲修。** start 的业务输入是 action/attemptId/locale，已在 effect；actionState 旧方法只使用共享 ref/setter、固定错误映射。autoLoader 取消旧微任务，generation 拒绝迟到结果。加每次重建的 start 会反复清输入和发验证码。保留并精确记录理由；若日后重构，先抽稳定加载接口且回归调度，不在本轮为了零警告改认证。 | Strict Mode 重放只加载一次；换语言/attempt 作废旧请求；卸载取消延迟任务；普通输入不重新挑战。 |
| 15 | `platform/auth/captcha-panel.tsx:194`，verifyForSubmit 使 imperative handle 每次更新 | **认证历史，当前语义正确。** 新 handle 有意读最新 canVerify/challenge/输入，不是陈旧闭包。若整理可移除 useImperativeHandle 的第三参数，使“每次提交更新 handle”显式；不要强行 memo 成旧状态。 | 当前答案、disabled/pending 变化立即影响提交；无旧 proof 被使用。 |
| 16 | 同文件:199，refresh 使 handle 每次更新 | 与 #15 同一 handle 更新原因；refresh 必须读当前 challenge 类型。可一起按每次提交更新处理。 | 最新 AUDIO/IMAGE 类型刷新正确；不能刷新已退休 challenge。 |
| 17 | `platform/auth/login-page.tsx:311`，continueLogin/loginActionEpoch | **认证历史，保留 epoch 快照语义。** epoch 由 useState lazy initializer 建立，身份稳定；restorePendingLogin 已把受控 revision 显式传给 continueLogin，且有 AbortController。可以单独补稳定 epoch；不要直接补重建的 continueLogin 造成 session 探测循环。若以后清理，抽依赖稳定 epoch 的 applyLoginResult(result,nextPath,expectedRevision)，所有异步调用仍传原操作 revision。 | pending 登录恢复、401 epoch 续接、Strict Mode、换号/卸载后迟到结果均不能继续旧登录。 |
| 18 | `platform/auth/login-page.tsx:358`，continueLogin | **认证历史，location state 一次消费的理由有效。** 该 effect 按 state 变化处理 WeChat 结果；直接依赖每 render 新函数会重复消费结果。可与 #17 共用显式 revision 的稳定 dispatcher，但必须保持一次性处理及旧 epoch 拒绝。当前不为消警告改流程。 | 同一个 state 普通 render 不再处理；新 state 处理一次；会话边界后旧回调不被重新接受。 |
| 19 | `platform/auth/preauth-enrollment-form.tsx:39`，action | **认证历史，闭包所需只有 setError。** 解构 setError 后依赖 `[setup, setError]`；最好与 #10 一起先稳定生产者。现函数每 render 换身份会重排 timer，但绝对 expiresAt 保持到期时刻，不能说已造成延长有效期。 | 到期清 setup/code 并报过期；普通输入不重建计时器；卸载取消。 |
| 20 | `platform/components/resource-table.tsx:99`，columns/request/searchInitialValues | **共享历史，本轮已补最终合并值签名。** signature 仅覆盖 requestSearch，不含 searchInitialValues。组织页独立 URL 筛选覆盖值改变时，表格结果变但搜索控件不同步。应基于最终合并值及必要字段类型生成 key，用已提交值快照更新表单，保留 dayjs 类型；不能加全部新对象而每 render 重置用户正在输入的条件。 | 组织页 Back/Forward 三类过滤条件；日期区间仍是 dayjs；未提交搜索文字不会被分页/loading/locale render 清掉。 |
| 21 | `platform/components/route-tabs.tsx:31`，activeTab | **共享历史，两个标量已覆盖行为。** 解构 activeTabPath/activeTabKey，effect 只用这两个值，依赖它们与 pathname；不要依赖含 children 的整个对象。 | 深链/hash/query 保留；不存在 tab 不跳转；已离页旧 effect 不改新地址。 |
| 22 | `platform/providers.tsx:181`，updatePreferences | **本轮清单核心，本轮已安全修正。** updatePreferences 已 useCallback([])，仅操作稳定 ref/setter。迁移 effect 依赖改为 `[hasSavedPreferences, updatePreferences]`，行为不变。 | 旧 localStorage 迁移一次；storage 监听正确解绑；普通 render 不重复迁移。 |
| 23 | `platform/providers.tsx:235`，staticLocaleRevision “多余” | **本轮清单核心，有效主动失效依赖。** loadLocaleMessages 修改模块缓存，不改 locale/runtimeMessages；revision 必须让 t 身份更新，从而让 memo/context 消费者获取本地化词典。保留并在项目审查记录说明；不要删去以消警告。长期可返回显式词典快照，届时移除人工 revision。 | 首次选择未加载语言，加载后从英文回退切至目标语言，且 memoized 子组件也更新。 |
| 24 | `platform/routing/use-tab-anchor.ts:31`，keys/scope | **共享历史，现有派生关系覆盖实际输入，但表达不透明。** name 由 scope 单射映射，join key 代表 keys；显式增加 scope 并改命名 JSON key，effect 内还原 keys。保留读取最新 window URL 和 pathname 离页护栏。 | scope 改变、非法 tab fallback、两个 tab group 同时更新互不覆盖、离页旧 effect 不导航。 |
| 25 | 同文件:31，keys.join 复杂依赖 | 与 #24 同一 effect。`const keysKey = JSON.stringify(keys)`，effect 内 `const currentKeys = JSON.parse(keysKey) as Key[]`；依赖 keysKey 而非每次新建数组，避免循环并消除分隔符歧义。 | keys 内容不变不反复替换 history；内容变更校正无效 tab。 |
| 26 | `plugins/localization/frontend/localization-page.tsx:148`，localeOptions | **插件历史，有效派生依赖。** options 仅依赖 locales.data 与静态 languages，现有 `[locales.data, locale]` 已覆盖变化。可 memoize localeOptions by locales.data 并显式依赖它；无需改权限或 locale 能力检查。 | 删除当前语言选首项；有效选择不变；空服务端数组使用 built-in fallback。 |


## 后续修复与验收边界

- ResourceTable 已按合并后的真实搜索值生成完整签名，包括searchInitialValues；只从已提交渲染的快照同步表单，保留Date/dayjs对象。新增URL前进后退、等值签名、日期类型与无效日期纯逻辑回归；真实表单和浏览器历史操作仍待界面验收。
- UserEditor 已将基本资料和扩展字段的显示快照绑定到提交基线。后台同ID新版本或schema变更触发既有冲突提示并禁止提交，显式刷新后才重建快照；直接提交也检查禁用条件。新增旧草稿/新版本与扩展字段状态回归，未将纯逻辑测试称为已完成浏览器交互验收。

表格仍保留审查基线的原因与修法，便于追溯。新代码不因历史告警存在而获得豁免；不得降低规则或删除安全护栏来消除告警。
