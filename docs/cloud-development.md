# Codex 云端开发

此配置用于 Codex 云端的独立 Ubuntu 开发环境。数据库和管理员密码在云端生成；不复制本机 `.runtime`、数据库或上传文件。它不是生产部署配置。

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
- `packages.adoptium.net`
- `www.postgresql.org`
- `apt.postgresql.org`

安装器支持 Ubuntu，要求 root 或免交互 sudo。已有 Node 24 可直接复用，否则安装官方 Node 24 下载包并校验 SHA-256；JDK 25 和 PostgreSQL 18 使用各自官方签名 apt 源。pnpm 固定为 10.33.0。

云端 PostgreSQL 18 main 集群必须使用 5432；端口冲突或配置指向其他数据库时停止并报错，不重置数据库。应用配置和密钥保存在权限受限的 `.runtime/local.json`。新环境首次启动会自动应用 Flyway 迁移，并在确认没有旧密文时生成开发加密密钥。已有密码及密钥不会替换。

安装脚本执行前端类型检查、测试、构建和启用数据库集成测试的 Maven verify。启动脚本刷新锁文件依赖、构建当前后端、启动两个服务并检查健康；不要将安装时的构建结果当作新任务当前源码的验证结果。

## 开发任务命令

```sh
bash scripts/cloud-start.sh
bash scripts/cloud-check.sh
source scripts/cloud-env.sh
node scripts/status.mjs
python3 scripts/smoke.py
node scripts/stop.mjs
```

临时日志、截图和测试输出保存在 `.runtime`，不提交到仓库。云端的 `127.0.0.1` 是云端 VM 内部地址，不能作为本机浏览器可直接访问的公网预览地址。

安装和启动通过后，在环境界面保存并发布；随后用一个新任务再验证启动。修改配置后重新发布，新任务才使用更新后的环境。

官方说明：[Codex Cloud environments](https://learn.chatgpt.com/docs/environments/cloud-environments)、[PostgreSQL Ubuntu 安装](https://www.postgresql.org/download/linux/ubuntu/)、[Temurin Linux 安装](https://adoptium.net/installation/linux/)。
