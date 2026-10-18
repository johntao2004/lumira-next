# 文件在线预览

我的文件和管理员全部文件中心共用预览弹窗。点击 READY 状态的文件名打开预览；回收站、上传中和待清理文件不提供预览。

## 格式与 API

- PNG、JPG/JPEG、GIF、BMP：服务器校验实际格式与扩展名，解码后输出 PNG 像素。JPEG 按 EXIF 方向显示；GIF 显示第一帧。预览图片最长边不超过 2048 像素，原始下载不变。
- PDF：文件中心通过权限保护的原文接口读取 Blob，由本地自托管的 PDF.js 在画布中直接解析和渲染原 PDF，提供翻页、缩放和适合宽度。不会逐页请求 JSON/PNG，也不会用图片弹层放大。PDF.js worker、CMap、标准字体、ICC、WASM 资源和顶层许可证由安装的依赖在 Umi 配置加载时复制到 `public/pdfjs/<version>/`，并使用版本化 URL，避免 worker 与主模块版本错配；不访问外部 CDN，WASM 执行保持关闭以兼容当前 CSP。文件关闭、切换、会话 epoch 或授权上下文变化时会中止原文请求、销毁 PDF.js loading task 并清空画布。
- TXT、Markdown、CSV、JSON、LOG、XML、YAML：读取 UTF-8，支持 UTF-8 和 UTF-16 LE/BE 的 BOM。Markdown 使用受控 React 渲染，其余作为纯文本显示。
- DOC、DOCX：Apache POI 在独立 Office worker 中提取文字，不承诺复现 Office 的分页与排版。
- 其他格式返回不支持在线预览；下载仍使用原始文件。HTML、SVG、宏和脚本不作为可执行网页嵌入。

`GET /api/account/files/{id}/content` 要求当前账号拥有文件。管理员 PDF 原文通过 `GET /api/platform/files/{id}/content` 获取，除预览权限外还要求独立的 `FILE_DOWNLOAD` 操作权限；没有该权限时，前端显示受控提示，不请求原文。角色预览期间不读取 PDF 原文：该模式只允许现有的一次性 JSON 预览资源票据，不为原文或下载签发票据，PDF 预览会显示受控不可用提示。

`GET /api/account/files/{id}/preview?page=1` 与 `GET /api/platform/files/{id}/preview?page=1` 仍保留为 JSON 预览接口：前者要求当前账号拥有文件，后者要求 `platform:files:read`。接口保持既有图片、文本和逐页 PDF PNG 响应契约，供现有客户端/兼容调用使用；当前文件中心的 PDF.js 原文预览不调用这两个接口。角色预览的一次性票据仍只签发并读取这些 JSON 预览资源，不会扩大到 `/content`。预览接口返回 `Cache-Control: no-store`。

Linux 后端运行环境安装 `fonts-wqy-zenhei`，用于保留的 JSON 预览接口通过 PDFBox 渲染未嵌入的 Adobe-GB1 中文 CID 字体；PDF 内已嵌入的字体仍优先使用。文件中心 PDF.js 使用浏览器 FontFace 和操作系统字体替代未嵌入的 CID 字体；其 CMap 等数据来自本地 PDF.js 资源。Ubuntu/WSL 本地可运行 `sudo apt-get install fontconfig fonts-wqy-zenhei`。无 sudo 的云安装会从发行版签名仓库提取字体到 PDFBox 可扫描的 `$HOME/.fonts`；单独放在 XDG 字体目录不会被当前 PDFBox 版本发现。安装字体后需重启后端，让 PDFBox 重新扫描字体。

JSON 预览响应包含 `text`、`truncated`、`imageDataUrl`、`page`、`pageCount`。纯文本的 `imageDataUrl` 为 null；图片和兼容接口中的 PDF 页面 `imageDataUrl` 只包含重新编码的 `data:image/png;base64,...`。PDF 页码从 1 开始，每次只渲染请求的一页。文件中心 PDF.js 查看器直接加载原文，不经过这个分页 JSON 响应。

源文件上限沿用上传的 20 MiB；纯文本最多展示 200,000 个 Unicode 码点。AI 附件继续使用 5 MiB 上限，并复用同一 DOC/DOCX worker。源图片最长边不超过 8192 像素且总像素不超过 16,000,000；JSON 兼容接口的图片与 PDF 输出最长边不超过 2048 像素。文件中心的 PDF.js 预览按当前页与缩放重新渲染原 PDF，并将单页画布限制在 16,000,000 像素内。解码不会写回文件或修改数据库。

## Office worker 资源边界

所有 DOC、DOCX、XLS、XLSX 的 POI 解析都发生在单独的 Office worker 容器。worker 容器的 cgroup `memory.max` 固定为 1 GiB，`memory.swap.max` 固定为 0，CPU 上限为 1 核、进程数上限为 128；服务启动时读取当前 Linux cgroup 文件并拒绝缺失、无限制或不符合配置的边界。这个 1 GiB 限制适用于 worker JVM、一次性解析子进程及其总内存，不是 RSS 轮询，也不依赖宿主 JVM 的 `-Xmx`。解析子进程每个请求新建，堆上限 512 MiB，最长运行 20 秒；超时会强制终止进程树并清理请求临时目录。worker 的单解析并发为 1，服务队列有界；宿主 client 也只允许一个 Office 请求在途，不排队。

worker 只在 `/v1/office` 接收带独立 token 的预览、字典解析、模板检查、模板 PDF 生成和工作簿读写请求，具体格式见[模板中心说明](template-center.md)。认证在读取请求正文前完成；从开始读取正文起，正文读取与一次性解析子进程共用 35 秒请求预算，正文最多 20 MiB，子进程另有最多 20 秒上限且受剩余预算约束。JDK HTTP server 另对请求头与正文总计设 45 秒时限、响应设 30 秒时限，并将连接数限制为 32；拒绝或超时后不会排空剩余上传正文。worker 只接收白名单内的 operations，不将 token 或上传内容写入日志，也不会把数据库、AI provider 或应用签名凭据传给解析子进程。响应最多 64 MiB；宿主通过带字节上限的 HTTP 流读取，并在 JSON model binding 前限制协议字段、字典行数和单元格长度。连接不跟随重定向。worker 不可用、繁忙、超时或被 cgroup OOM 终止时，宿主返回通用暂不可用错误；不会在宿主 JVM 中继续解析。

DOCX 预览与 XLSX 字典导入会在 worker 内创建 POI 对象前逐输入扫描 ZIP 实际解压流：最多 2,048 个条目、单条目 32 MiB、总解压量 96 MiB、XML 合计 32 MiB、XML 深度 128、XML 解析事件与属性合计 5,000,000；超过 1 MiB 的条目压缩比不得高于 200:1。检查不依赖中央目录的未压缩大小声明，并禁用 DTD 与外部实体。超预算保留 `filePreviewTooLarge` 或 `dictionaryDatasetTooLarge`。DOC 和 XLS 使用 OLE2，不运行 ZIP 预检，但仍受输入限制、单请求进程、20 秒终止和 worker cgroup 硬内存边界保护。

本地与云端启动要求运行中的 Docker Engine 和 Docker Compose v2。`scripts/start.mjs` 管理独立 worker 和固定 TCP ingress sidecar：宿主端口只绑定 `127.0.0.1`，sidecar 只把连接转发到同一 Compose 项目的 `office-worker:18091`，不接受可变目标或 CONNECT；worker 仅连接无外网路由的 internal private network，只有 ingress sidecar 加入发布网络。启动会等待 worker 与 sidecar 健康，并从宿主通过已发布端口检查 `/health`。`scripts/stop.mjs` 只操作名称、Compose project、owner/profile 标签与本地 profile 配置均匹配的资源。token 存在 `.runtime/office-worker/local.json`，权限为 0600，目录权限为 0700，不写 `.runtime/local.json`。生产 `deploy/compose.yml` 使用独立 `office-worker` 服务、不可公网访问的内部网络和必填的 `LUMIRA_OFFICE_WORKER_TOKEN`。`deploy/office-worker.Dockerfile` 在隔离的 JDK builder stage 中从当前源码构建后端 JAR，再复制到精简 JRE runtime；builder 安装 `curl` 与 `unzip` 以使用带 SHA-256 校验的 Maven Wrapper。它不运行前端或插件打包流程。

## 验证边界

`pnpm test:java` 使用真实、受 cgroup 限制的 worker 执行 Java 测试；本地未提供 `LUMIRA_OFFICE_WORKER_URL` 和 `LUMIRA_OFFICE_WORKER_TOKEN` 时，它会在 18092 启动独立临时 worker 并在测试后停止。测试环境可传入成对的 URL/token，让测试访问独立 QA worker；脚本不会停止外部 worker。`FilePreviewReaderTest` 覆盖合法 DOC 与 DOCX，`DictionaryFileParserTest` 覆盖合法 XLS 与 XLSX，`AiAttachmentReaderTest` 覆盖 AI 附件复用；这些格式回归不模拟 POI 结果。`OfficeWorkerClientTest` 与 `OfficeWorkerResponseGuardTest` 使用假 HTTP 端点或协议 JSON，只验证宿主 client 的认证头传递、错误映射、边界和结构拒绝，不证明容器 cgroup 边界。容器限额、worker 超时/OOM恢复及当前宿主服务存活需通过真实容器验收单独验证。

图片像素、扩展名伪装、分页扫描 PDF 和越界页码由 `FilePreviewMediaTest` 覆盖；文件所属账号、回收站与管理员全局权限由 `FileServiceTest` 与 `FilePreviewAuthorizationTest` 覆盖。HTTP 和浏览器验证独立进行，不能以构建替代图片显示和翻页检查。
