# 全局敏感词管控

平台共用一套敏感词词库。角色只配置是否启用管控，不复制词库，也不配置各自的分类或处理动作。启用词条的 `BLOCK` 动作拒绝提交，`LOG_ONLY` 允许提交并记录命中。

角色未配置时默认启用；没有有效角色的账号也默认执行共享词库。一个账号拥有多个有效角色时，只要其中一个启用就执行管控，所有有效角色都关闭时跳过检测。停用角色不参与判断。角色开关、角色归属及词库变更在后续请求中生效，无需重新登录。敏感词管理权限与角色管控开关分别处理，关闭管控不会授予词库读取或修改权限。

## 提交边界

`ContentPolicyPort` 是公共检测接口。敏感词模块通过 `RoleContentPolicyService` 提供实现，`GlobalContentPolicyAdvice` 在已认证的 JSON 请求进入控制器之前检查业务文本，文件上传参数由 `ContentPolicyParameterInterceptor` 检查。业务模块不需要在各自对话框或服务中调用词库。

JSON 文本字段包括 name、displayName、realName、title、description、detailHtml、bodyHtml、body、content、adminReply、message、text、comment、remark、reason、label、defaultMessage、translations 和 values，以及字典值、地区、导航显示文字、设置变更原因、资料字段提示/分组/选项、站点/页脚文字、协议 Markdown、水印文字和 AI 判断指令/选项/含义。识别的嵌套对象和数组递归处理；富文本先清理并提取可见文字，HTML 实体和行内格式拆分不能绕过匹配。增加新类型的业务文本时，应维护该字段契约并补充提交测试。

检测覆盖资料、反馈、AI 消息、通知、字典、本地化、用户和角色名称等 JSON 文本，以及上传文件名。密码、令牌、密钥、认证凭据和账号标识不参与扫描。当前不解析上传文件正文、图片文字或历史已存内容；若需对文档正文或 OCR 管控，应另行接入完整内容提取流程，不能使用截断的预览文本替代。

敏感词和角色策略管理控制器使用 `ContentPolicyExempt`：管理员必须能够配置包含敏感词的规则。模块关闭时提交检测跳过，模块的管理接口仍受原有模块可用性与权限检查约束。

## 管理与记录

`/dashboard/sensitive-words` 提供角色管控、共享词库、命中记录、策略试运行。试运行是辅助验证，不替代真实业务提交。只有具有独立全局敏感词权限的管理员可访问；读取、写入与导入权限继续分别校验。

`/api/platform/sensitive-policies/roles` 读取角色开关；`PUT /roles/{id}` 使用 `{mode:"ON"|"OFF",version}` 乐观更新。`/hits` 分页读取命中元数据；`/preview` 使用所选角色开关或直接测试共享词库，不生成提交命中记录。

命中表只保存账号、入口、动作、匹配数量、角色 ID 与时间，不保存提交原文、密码或词条原文。阻断记录使用独立事务保存，即使提交被拒绝也可追踪。客户端统一将 `sensitiveContent` 映射为清晰的拒绝提示。

宿主初始化基线 `B52__platform_initialization.sql` 提供角色管控开关和命中元数据的最终表结构及稳定系统种子。未配置的角色仍通过查询默认值启用管控。
