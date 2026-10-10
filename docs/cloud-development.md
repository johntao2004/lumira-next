# Codex 云端开发

此配置用于 Codex 云端的独立 Ubuntu 或 Debian 开发环境。数据库和管理员密码在云端生成；不复制本机 `.runtime`、数据库或上传文件。它不是生产部署配置。

云端开发还需要 Docker Engine 与 Docker Compose v2，并要求当前 VM 用户可访问正在运行的 daemon。安装入口会先检查这两项；它不会在 VM 中自动安装 Docker。请先在一次性 VM 安装并启动 Docker，使用同一用户确认 `docker info` 和 `docker compose version` 成功，再运行 Lumira 安装脚本。Office 解析不使用宿主 JVM 回退。

## 环境配置

先将需要继续开发的源码提交并同步到 GitHub。创建云端环境时选择对应仓库，安装脚本填写：

```sh
LUMIRA_CLOUD_DEV=1 bash scripts/cloud-install.sh
```

Start skill 填写：

```text
在仓库根目录运行 bash scripts/cloud-start.sh。
如果命令失败，检查 .runtime/backend.log 和 .runtime/web.log，修复后重试。
确认后端 http://127.0.0.1:8090/actuator/health/readiness 和前端
http://127.0.0.1:8010/user/login 均返回 HTTP 200，再开始依赖服务的开发任务。
后端改动后重新运行启动命令，让运行进程加载新 JAR。
```

允许联网，选择 Package managers，并添加以下下载域名：

- `nodejs.org`
- `maven-central.storage-download.googleapis.com`
- `api.adoptium.net`
- `github.com`
- `release-assets.githubusercontent.com`
- `ftp.postgresql.org`
- `packages.adoptium.net`
- `www.postgresql.org`
- `apt.postgresql.org`

安装器支持 Ubuntu 和 Debian。Debian 普通用户默认使用无需 sudo 的方案：工具链保存在仓库之外的 `/workspace/lumira-tools/linux`（其他 VM 使用 `$HOME/.local/share/lumira-cloud`，可用 `LUMIRA_CLOUD_TOOLS_DIR` 指定）；Node 24 和 Temurin JDK 25 下载包验证 SHA-256，PostgreSQL 18.6 从官方源码校验后编译，保留 zlib，省去开发环境不需要的 ICU 和 psql 行编辑。缺少的 flex、bison、m4 使用系统提供的官方发行版签名密钥，在工具目录内建立隔离 apt 索引、下载并解包；不读取受限的系统 apt 配置，不修改系统。VM 需提供 gcc、make、perl、python3、curl、tar、sha256sum、dpkg-deb 和 apt-get。Ubuntu 普通用户可加 `LUMIRA_CLOUD_USERSPACE=1` 使用同一方案；原有 Ubuntu root/免交互 sudo 方案仍使用官方签名 apt 源。pnpm 固定为 10.33.0。npm/pnpm 元数据缓存显式放在工具目录，并复用已有 workspace 的 pnpm store。Maven Wrapper 下载目录和依赖缓存显式放在工具目录，不依赖可写的 `~/.m2`。云端默认使用 [Google 官方 Maven Central 镜像](https://docs.cloud.google.com/docs/buildpacks/java#google_cloud_hosted_maven_central_mirror)，避免共享出口 Central 429；Wrapper 发行包固定 SHA-256。Maven 在 VM 提供 `/etc/ssl/certs/java/cacerts` 时使用现有系统 Java 信任库，保持 TLS 验证；不导入未经信任的证书。可用 `LUMIRA_CLOUD_MAVEN_MIRROR` 选择 `https://repo.maven.apache.org/maven2` 或 `https://repo1.maven.org/maven2`。每次加载 cloud-env 时从当前云端代理环境生成权限 0600 的 Maven settings，避免把临时代理凭据写入源码或固化在快照中。

云端独立 PostgreSQL 18 集群必须使用 5432；端口冲突或配置指向其他数据库时停止并报错，不重置数据库。普通用户集群位于 `.runtime/cloud-postgres`，只监听回环地址；Unix socket 目录权限 0700，TCP 使用 SCRAM 密码认证。应用配置和密钥保存在权限受限的 `.runtime/local.json`。新环境首次启动会自动应用 Flyway 迁移，并在确认没有旧密文时生成开发加密密钥。已有密码及密钥不会替换。

安装脚本执行前端类型检查、测试、构建和启用数据库集成测试的 Maven verify。Maven Office 格式回归通过真实的 1 GiB cgroup 限制 worker 执行；`scripts/office-worker-test.mjs` 先从当前源码重新 package 后端 JAR，再创建独立临时 worker/token 并在验证后停止，或使用成对传入的 `LUMIRA_OFFICE_WORKER_URL` 与 `LUMIRA_OFFICE_WORKER_TOKEN` 指向一次性 QA worker。启动脚本刷新锁文件依赖、构建当前后端，启动一个 Docker Office worker 和两个应用服务并检查健康；worker token 单独保存在 `.runtime/office-worker/`，不写入 `.runtime/local.json`。独立 worker Dockerfile 在 builder stage 从 Docker build context 中的当前源码构建 JAR，因此干净 checkout 不要求预先存在 `backend/target`；Maven Wrapper 会校验发行包 SHA-256。不要将安装时的构建结果当作新任务当前源码的验证结果。

## 开发任务命令

```sh
bash scripts/cloud-start.sh
bash scripts/cloud-check.sh
pnpm test:java
source scripts/cloud-env.sh
node scripts/status.mjs
python3 scripts/smoke.py
bash scripts/cloud-stop.sh
```

临时日志、截图和测试输出保存在 `.runtime`，不提交到仓库。云端的 `127.0.0.1` 是云端 VM 内部地址，不能作为本机浏览器可直接访问的公网预览地址。

安装和启动通过后，运行 `bash scripts/cloud-stop.sh` 正常关闭服务和数据库，保证文件系统快照一致，再在环境界面保存并发布；随后用一个新任务再验证启动。修改配置后重新发布，新任务才使用更新后的环境。

官方说明：[Codex Cloud environments](https://learn.chatgpt.com/docs/environments/cloud-environments)、[PostgreSQL Ubuntu 安装](https://www.postgresql.org/download/linux/ubuntu/)、[Temurin Linux 安装](https://adoptium.net/installation/linux/)。
