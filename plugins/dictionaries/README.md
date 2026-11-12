# Dictionaries

字典元数据、字典项、层级查询、JSON 导入导出和文件导入管理由此独立插件提供。
宿主不编译这些控制器、仓库或业务服务；插件构建使用共享 SDK，并保留现有 API、
权限码和 `/dashboard/dictionaries` 路由。

`DictionaryReadServicePort` 是插件提供的共享只读契约。身份资料和设置通过宿主
`DictionaryKernelAdapter` 在每次调用时获取当前插件，调用租约持续至外层事务结束。
空的资料选项请求不需要安装字典；实际查询在插件不可用时明确失败。
文件解码使用宿主 `DictionaryImportPort`，XLS/XLSX 继续通过隔离 Office worker
解析，共享传输格式不依赖 Web 或数据库框架。

反馈、敏感词等消费者在各自清单中声明字典依赖，必须逐一卸载消费者后才能卸载
字典。设置中存在绑定字典的资料字段时，当前配置引用也会阻止禁用和卸载；管理员
需要先手动修改字段绑定。卸载计划包含引用快照，排空调用后会再次检查，不自动解除
业务依赖。

字典表由现有 B52 初始化管理，插件不改写全局迁移、历史校验和或现有数据，也不
声明可删除的数据集。保数据卸载只移除插件运行能力，重装读取原字典 UUID、层级和
版本。内置导航仅接管 B52 中对应的固定菜单记录。

新数据库通过 `plugins/bootstrap.json` 安装。已有环境切换代码前，需要在隔离副本
验证新包和依赖图，并准备对应插件包；启动不会把曾由管理员卸载的插件自动装回。
固定图部署必须在批准清单中包含精确的包摘要。

构建：`pnpm plugins:build`。数据库装卸验收：
`python3 scripts/dictionaries-plugin-lifecycle-smoke.py`；浏览器导入导出验收：
`node scripts/dictionary-browser-smoke.mjs`。两者都需要绑定独立 QA 数据库的私有配置
和 fixture guard，不能使用默认本地业务数据库。
