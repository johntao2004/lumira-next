# 运行展示屏示例插件

runtime-observer 是独立的 SDK 示例插件。安装并启用后，它在“运维”导航中提供一个只读运行面板；停用或卸载插件即可撤下页面。它有自己的页面访问权限 platform:runtime-observer:access 和读取操作 platform:runtime-observer:read，仍需分别持有宿主监控、模块管理或事件队列的全局读取权限，才能显示对应数据。

面板调用宿主只读接口：

- GET /api/platform/monitoring/summary：运行摘要、机器与数据库状态、Outbox 事件类型覆盖和最近 8 条事件元数据；需要 platform:monitoring:access。
- GET /api/platform/modules 与 GET /api/platform/modules/plugins：模块和插件制品状态及依赖；需要 platform:modules:access。
- GET /api/platform/event-outbox?page=1&pageSize=8：事件队列元数据；需要 platform:event-outbox:access。页面只显示类型、状态、尝试次数和时间，不显示事件键、负载或错误详情。
- GET /api/auth/methods 与 GET /api/auth/registration/config：公开认证方式、注册可用状态、渠道和验证码策略。页面不创建挑战、不发验证码，也不读取个人认证、在线会话或用户身份轨迹。

定时读取只在页面前台发生，默认每 3 秒轮询；失败后逐步退避，用户可以暂停或手动刷新。趋势图只保留当前页面最多 30 个测量点，离开页面或认证上下文变化时会清除。缺少数据或权限时显示未知或无权限，不用零值代替。

依赖图只根据宿主模块列表和插件清单中的真实依赖构造，依赖方向为“依赖项 → 使用方”。接口未返回的依赖显示为未知节点。事件图仅展示生产/消费事件类型计数、宿主报告的缺少消费者类型及队列状态；宿主没有提供逐消费者连线或处理耗时，页面不会模拟这类细节。认证部分只展示公开配置流程，不追踪具体用户的登录、找回、手机号绑定或敏感操作重验请求；二次认证仍按全局/角色策略执行。

插件没有自己的 HTTP 控制器、持久业务表或写入操作。插件迁移只创建插件自有的导航条目，并在 SQL 中检查导航代码归属，避免接管其他模块的条目。

## 定向构建

此插件位于 plugins/sdk/examples/，不会进入默认生产插件包图。使用 Node 24、pnpm 10.33.0 和 JDK 25，在仓库根目录运行：

node scripts/build-plugins.mjs --no-host sdk/examples/runtime-observer

测试只覆盖纯数据整理和清单/导航契约：

node --test plugins/sdk/examples/runtime-observer/tests/*.test.mjs
