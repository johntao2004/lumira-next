# 模块与公开开发包

## 当前目录与职责

- `framework/platform-contracts`：宿主与受信模块共享的版本化端口、不可变授权事实、页面/操作标识，以及明确列出的纯工具。它不含数据库 Repository、部署密钥或宿主装配。
- `framework/plugin-sdk`：PF4J 入口与生命周期资源契约。
- `framework/payment-sdk`：支付扩展专用 SPI、客户端及纯工具，普通插件无需依赖。
- `framework/plugin-build`：现有插件使用的显式编译依赖集合，不含宿主 JAR/classes。
- `backend`：宿主启动装配、身份/权限执行、数据访问、事件调度和插件运行时。API 端口的实现仍在所属功能包。
- `plugins/<id>`：插件清单、后端、前端和自己的初始化 SQL。
- `apps/web/src/app`：宿主业务组合；`platform`：通用传输、身份状态、路由/插件运行时；`features`：静态功能页面；`pages`：框架路由入口。
- `examples/independent-notes`：可复制到仓库外的中性独立应用，只依赖公开 contracts 和声明的三方库。
- `docs/architecture`：当前逻辑分层图及有阶段门槛的演进路线图。图中分区不是独立进程要求。

Java 公共类保留原 FQN，便于现有源码及插件二进制继续解析；源码物理归属已经独立。不能为了目录好看随意更名公共 FQN。宿主的 `SecretKeys`、`ClientAddressResolver`、`FixedWindowBuckets` 仍留在宿主实现；插件使用 `SecretCryptoPort`、`ClientAddressPort`、`RateLimitKeys`。密钥格式、缺配置失败及代理信任决策没有转移到插件。

## 本地构建和版本

候选版本为 `0.1.0-alpha.1`，仅本地安装验证，不代表已发布到任何远端仓库，也不承诺已覆盖任意历史插件版本。

```sh
./backend/mvnw -f framework/pom.xml install
./backend/mvnw -f pom.xml -pl backend -am -DskipTests package
pnpm plugins:build
./backend/mvnw -f examples/independent-notes/pom.xml verify
```

`plugins:build` 自行准备版本化 SDK 和第三方 classpath，绝不引用 `backend/target/classes`。旧 `--no-host` 参数保留为兼容参数；插件编译本来就不构建宿主。开发包不打包进插件，运行时由宿主提供同一份公共类型，避免类身份冲突。宿主仍然需要这些 artifact 作为普通 Maven 依赖。

插件格式的 `sdkVersion: "1"` 是现有运行时协议主版本，独立 Maven artifact 的候选版本不直接替代该字段。破坏性契约变更必须同时评审协议、API/二进制兼容、宿主和插件版本矩阵；不能改清单数字冒充兼容。

## 边界约束

插件仅能引用独立公开类型及声明的模块 API，不允许按 `common.*` 或 `modules.*` 全包放行。构建依赖和源代码检查都执行限制；受信插件与宿主同进程共享数据库，这不是恶意代码沙箱。

- 授权快照只表示服务端已确认的事实，不能由前端或插件清单自行授予。
- 敏感服务执行授权和数据范围检查，HTTP 路由规则只是第一层。
- 业务事务显式声明；事件消费者的数据库效果与 ACK 同事务。网络副作用必须走独立持久发送机制，不能声称 exactly-once。
- 当前消费者集合在宿主启动时装配。动态插件声明一个消费者 Bean 不会自动热订阅。
- 插件停用/卸载必须撤销路由、页面和托管资源，迟到脚本不能恢复旧页面。
- 不增加万能 common/utils 容器。新端口归属明确，新增共享能力需证明至少两个真实消费者或清晰的独立应用边界。

## 独立样例与升级演练

独立笔记应用的身份源和 `notes_*` 表属于自己，不继承平台用户的权限。读取与写入分开，业务服务重验权限，保留 CSRF；密码必须显式配置且不同，无默认账号密码。它是框架独立消费示例，不是已完成产品化的第二套管理平台。

V1/V2 只演示该应用自己的受支持基线到下一版本。全局 B52 和插件既有初始化身份/校验和保持原样。不能将样例通过解释为未知旧生产库已经可升级。

`python3 scripts/verify-independent-app.py` 在本地 PostgreSQL 测试环境运行，要求 PGHOST/PGPORT/PGUSER/PGPASSWORD 环境变量；它仅创建随机 QA 库，复制源码到仓库外构建，再执行真实 HTTP 权限、V1→V2、pg_dump/restore、恢复后读取与只读校验。不要将生产凭据传给测试脚本。

## 必跑检查

```sh
pnpm check:core-comments
python3 scripts/plugin_boundaries.py
python3 scripts/module-boundaries-smoke.py
pnpm check:java-format
pnpm typecheck
pnpm test
pnpm build
pnpm plugins:build
pnpm test:java
```

另须执行独立应用验证及实际宿主角色权限、插件生命周期、恢复和 UI 验收；环境阻断不能改测试期望或取消隔离。生产准入以 [验收清单](production-readiness.md) 为准。
