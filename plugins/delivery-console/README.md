# 投递记录插件

`delivery-console` 提供邮件、短信投递元数据查询及既有页签页面，依赖 platform 和 communication。使用 `node scripts/build-plugins.mjs delivery-console` 构建，通过插件管理导入安装。

卸载保留投递记录，不声明删除数据集。认证验证码发送端口、邮件和短信投递 worker 及状态机由共享基础层负责，管理页面的装卸不会删除队列。查询保留原有全局页面权限，不返回加密负载或租约令牌。
