# 敏感词策略插件

这是 Lumira 的独立插件，包含完整前后端源码与插件清单。

运行 `pnpm plugins:build sensitive-words`，安装包生成在 `dist/sensitive-words-1.0.5.lumira-plugin.zip`。在平台模块的可管理模块页导入并安装。

依赖：platform, dictionaries。默认卸载保留业务数据、配置、源码与发布包；选择保留数据后重新安装兼容版本恢复使用。

卸载时可选择保留数据，或删除清单 `dataSets` 声明的业务记录。删除不可恢复；依赖插件的数据、共享文件、权限授权及迁移记录保留。
