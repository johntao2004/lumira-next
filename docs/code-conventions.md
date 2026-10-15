# 源码命名、职责与复用约定

本项目采用各语言和框架的工程惯例，并用项目检查固定规则。它不是 ISO 或其他国际标准认证。

## 命名

| 对象 | 约定 | 示例 |
| --- | --- | --- |
| 前端 TS/TSX 文件 | 小写短横线，测试使用 .test/.spec 后缀 | use-crud-drawer.ts、organizations-page.tsx |
| React 组件、TypeScript 类型 | UpperCamelCase | OrganizationForm、UserSummary |
| 函数、变量和 Hook | lowerCamelCase，Hook 使用 use 前缀 | saveRecord、useSubmitAction |
| Java 文件和顶层类型 | UpperCamelCase，public 类型与文件同名 | SettingDocuments.java |
| Java package | 全小写，与 src/main/java 或 src/test/java 下的路径一致 | com.lumira.platform.roles.api |
| Java 方法、变量 | lowerCamelCase；常量 UPPER_SNAKE_CASE | expectedVersion、MAXIMUM_BYTES |
| 宿主全局数据库 | 当前唯一初始化文件为最终态 B52 基线 | B52__platform_initialization.sql |
| 插件 Flyway 迁移 | 各插件独立使用 V版本__下划线描述.sql，已发布脚本仅追加 | plugins/feedback/migrations/V4__unify_feedback_data_scope.sql |
| 命令脚本 | 小写短横线；可导入 Python 辅助模块用下划线 | source-naming-smoke.py、smoke_support.py |
| 框架配置与构建入口 | 保留工具指定名称 | .umirc.ts、package.json、pom.xml、Dockerfile |

使用 UTF-8、LF、空格缩进；Java/Python 为 4 空格，其余默认 2 空格，见根目录 `.editorconfig`。Java 格式化以 Spotless 的 AOSP 配置为准。文件名使用 kebab-case 不影响 React 组件函数使用 UpperCamelCase。

外部依赖、第三方资源、生成代码有各自来源，不机械重命名；`node_modules`、`dist`、`target`、`.umi*`、`.runtime` 不作为自有源码检查。临时报告和测试日志放在 `.runtime`。全局数据库测试使用唯一初始化 SQL 的当前结构，不另存历史迁移夹具。

## 目录和职责

- `apps/web/src/platform`：跨功能公共组件、请求、路由、表单、模块契约和壳层；不能依赖具体 feature 实现。
- `apps/web/src/features/<feature>`：宿主功能页面、字段与规则，路由由 manifest/module 声明。
- `plugins/<id>`：独立插件。前端仅使用 `plugins/sdk/frontend-modules.json` 暴露的宿主接口，不能导入宿主 feature 或其他插件实现。
- Java 使用 Maven 的 `src/main/java`、`src/main/resources`、`src/test/java` 布局。按功能划分包，禁止按技术层把所有功能混进一个巨大的全局 service/repository 目录。
- 小功能可在包内放置 Controller/Service/Repository；存在对外 DTO 时统一放在功能的 `api` 子包。不要为小模块机械增加空的 domain/application/infrastructure 层。
- Controller 负责 HTTP 参数、载荷限制、状态码与响应映射；Service 负责业务规则、权限和事务编排；Repository/Store 负责存储。响应 DTO 使用 `<资源>Response`，不直接把 Repository 的内部记录声明为控制器响应。
- 服务间调用使用已有 port/显式模块契约，跨模块依赖必须进入架构允许清单；不能为了复用让 common 反向依赖业务模块。

## CRUD 与设置复用

- 同页多个表单须设置独立的 `name`，避免编辑字段与表格搜索字段生成重复 ID；`id` 仍用于关联外部提交按钮。
- 新建/编辑抽屉使用 `useCrudDrawer<T>`：undefined 表示关闭、null 表示新增、T 表示编辑。`close` 和选择操作在提交期间受保护，`saved` 用于写入成功后的关闭。
- 受外部 open/selection 控制的表单和多步骤业务使用 `useSubmitAction`：统一 pending、错误映射和同步防重复提交。`run` 返回 false 表示拒绝、取消或失败；调用方应 await 或显式 void。成功后的缓存刷新也放入 run 范围。
- `saveRecord` 统一基于 ID 的 POST/PUT 选择；`pageAfterRemoval` 处理删除最后一行后的页码回退。版本、载荷映射、错误允许清单和缓存范围仍由业务模块声明。
- 配额影响确认、组织树、权限边界、密码和账号验证不能合并成万能 BaseCrud。复杂操作可共享执行生命周期，业务状态机保持明确。
- 常规 JSON 设置使用 `SettingDocuments` 完成类型化读取、缺省值、校验与序列化。`SettingStore` 继续统一权限、版本冲突、修订和审计；涉及密钥、策略缓存和多表事务的服务保留自身业务编排。
- `PageResult.map` 转换响应 DTO 时保留 total/page/pageSize。DTO 调整必须保持公开 JSON 契约，字段变更应作为接口变更处理。

## 自动检查

```sh
pnpm check:quality
pnpm check:java-format
pnpm typecheck
pnpm plugins:typecheck
pnpm test
./backend/mvnw -f backend/pom.xml verify
pnpm test:crud
```

`check:quality` 已接入 CI 和 `pnpm test`：

1. `source-naming-smoke.py` 检查自有前端/Java 文件名、Java 包路径和 public 类型、迁移名、脚本名及大小写路径冲突。
2. jscpd 5.4.0 检查宿主、插件与运维脚本中的相同 token 块（至少 100 tokens、12 行，忽略注释和 import/package）。测试/浏览器验收脚本、翻译资源和生成/第三方代码不计入业务重复。
3. `.jscpd-baseline.json` 是重复指纹基线，当前为空；达到配置阈值的重复使 CI 失败。它不是“零重复”证明，也不覆盖所有语义相似代码。不要提高阈值或自动更新基线来绕过失败；先查看 `.runtime/code-quality/duplicates/jscpd-report.json`，决定抽取或明确说明有意重复。移除旧重复后可以收缩基线，新增豁免必须在变更中解释理由。

`pnpm check:java-format` 依次运行 `backend/pom.xml` 和 `plugins/pom.xml` 的 Spotless 检查，不使用 ratchet，因此覆盖宿主与全部插件自己的 Java 源码。`plugins/pom.xml` 仅提供格式检查入口（`packaging=pom`），不是插件编译或发布工程；生成目录与 `node_modules` 会排除。CI 的 backend job 运行这一统一命令，随后照常执行宿主 Maven `verify`。

模块边界脚本与 ArchUnit 检查依赖关系；DTO JSON 契约测试检查响应映射。提交生命周期的浏览器验收需检查错误重试、取消确认、防重复提交、刷新持久化，并清理自建测试记录。构建、健康检查和浏览器验收是不同的验证层。

参考：[Maven 标准布局](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html)、[Google Java Style](https://google.github.io/styleguide/javaguide.html)、[React 组件命名](https://react.dev/learn/your-first-component)、[Umi 目录约定](https://umijs.org/docs/guides/directory-structure/)、[Flyway 版本迁移](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)、[jscpd 文档](https://github.com/kucherenko/jscpd)。
