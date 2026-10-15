# 后台 API 文档

后台页面 `/dashboard/api-docs` 使用服务端实时生成的 OpenAPI JSON，地址为 `GET /api/platform/api-docs`。后端固定使用 `org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.1`，只安装 MVC 规范生成器，不安装 Springdoc Swagger UI、Scalar 或任何默认文档界面。实现以实际 Spring MVC 映射和方法签名生成路径、HTTP 方法、查询/路径参数、请求体与响应 DTO，不维护一份固定手写 spec，也不生成真实密钥或凭据示例。

## 安全与认证

读取规范本身要求登录账号具有全局 `platform:api-docs:access`。Spring Security 先处理认证和 authority，再由 `ApiDocsAccessPolicy` 查询当前全局权限；匿名请求返回 401，缺权限或仅有组织/个人范围权限时返回 403。每次读取都重新校验权限，响应沿用安全头配置的 `Cache-Control: no-store`，不会被浏览器或共享代理复用。

受保护的业务接口通过 HttpOnly `LUMIRA_NEXT_SESSION` 会话 Cookie 认证。状态变更请求还要提交 CSRF token。客户端应先调用 `GET /api/auth/csrf`，使用响应中的 `headerName` 与 `token`；当前 header 名为 `X-CSRF-TOKEN`。公开的找回密码等写接口也需要 CSRF token。

OpenAPI 中的 cookie 与 CSRF schemes 描述传输方式。接口是否还要求某项平台 authority、全局范围或用户数据范围，仍由各接口的服务端规则决定；规范不声称完整反映逐路由 ACL。登录与退出由认证过滤器处理，不属于 Spring MVC `HandlerMethod`，不会作为自动扫描的 operations 出现。

## 路由范围与插件刷新

只扫描当前宿主 `RequestMappingHandlerMapping` 中匹配 `/api/**` 的 Spring MVC `HandlerMethod`。插件控制器实例在插件子上下文创建，但路由登记在宿主 mapping 中；每次生成规范时读取当时的 mapping 快照，不在 provider 中缓存插件 `HandlerMethod`。规范自身、`/api/health/**`、`/api/internal/**`、`/health/**`、`/internal/**` 和 `/actuator/**` 均排除。应用配置 `server.servlet.context-path` 时，路径仍相对于应用上下文；`servers` 使用当前来源的 `/`，生成路径保留完整 `/api/...` 前缀，不拼入内部端口或重复添加 `/api`。

Springdoc 的额外 controller 白名单是进程级静态集合且没有移除 API，因此只加入稳定的 JDK `Object.class`，绝不登记插件控制器类。该候选门槛由全局方法过滤器再限制为当前宿主映射中实际 handler bean 的类型带有 `@RestController` 或 `@ResponseBody`，或方法本身带有 `@ResponseBody` 的映射，并继续应用 `/api/**` 路径匹配和排除规则；这只影响规范生成，不改变 MVC 路由或服务端授权。过滤器每次只临时读取当前 mapping，不缓存 plugin handler 或类；因此继承自普通基类的方法会按实际插件 bean 子类上的 controller 注解判定。

插件运行时通过宿主 `RequestMappingHandlerMapping` 注册、注销动态路由。`springdoc.cache.disabled=true` 使每次读取重新计算规范；自定义的宿主 MVC provider 也在每次读取时重取 mapping 快照，避免 springdoc 默认 provider 对第一次映射结果的内部缓存。因此插件安装或卸载完成后，下一次刷新会看到当前映射。插件安装/卸载与读取并发时，当前响应可能赶在注册表切换前完成；再次刷新会与新映射一致。关闭缓存意味着每次访问都要重新扫描和解析 schema，页面应按需刷新。

对动态插件 GET operation，文档生成会调用当前 `PluginAuthorizationPort`，只有已启用插件清单明确允许匿名访问的路由才标记为无需会话；未注册、受保护或授权提供方不可用时仍按受保护路由显示 Cookie 认证。插件路径参数按 Spring 路径模板传入授权判断。

规范只覆盖 Spring MVC 映射。过滤器直接处理的认证端点、WebSocket、静态资源、Actuator 和其他非 MVC/upstream 接口不在生成范围内。DTO/schema 反映控制器 Java 方法签名及识别到的 JSON 注解；它不替代实现审查，也不证明每个错误分支、权限条件或运行时插件状态。

## 路由与配置

- `GET /api/platform/api-docs`：由全局 `platform:api-docs:access` 权限保护的 JSON 规范。
- Springdoc 默认 `/v3/api-docs*` 和 `/swagger-ui*` 路径显式拒绝访问；生成入口只保留上述 JSON endpoint。
- 规范端点以外的 HTTP 方法及其 YAML 路径拒绝访问。
- 宿主初始化基线 `B52__platform_initialization.sql` 提供带 `platform:api-docs:access` 权限的“后台 API 文档”导航项；页面和 JSON endpoint 均要求该权限及全局范围，页面路由归属 `monitoring` 模块。

关键配置位于 `backend/src/main/resources/application.yml`：`springdoc.api-docs.path`、`springdoc.paths-to-match`、`springdoc.paths-to-exclude` 和 `springdoc.cache.disabled`。更换 endpoint 时，必须同步更新安全匹配与排除路径，并保留默认入口拒绝规则。
