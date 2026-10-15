# 账户设置

账户设置参考 [Ant Design Pro 个人设置](https://preview.pro.ant.design/account/settings/)，使用一个容器背景色面板、分类导航和内容区。沿用 Lumira 的 ProLayout、Ant Design 主题和账号接口，不复制参考站点的示例账号、头像或业务字段。

## 入口与页面

头像下拉菜单只提供个人资料和退出。设置分类通过前端模块清单注册；分类切换使用路由，刷新和浏览器前进、后退应恢复当前页面。

| 分类 | 路由 |
| --- | --- |
| 个人资料 | `/dashboard/account-security` |
| 账号安全 | `/dashboard/account-authentication-security` |
| 角色预览 | `/dashboard/account-role-preview` |
| 微信绑定 | `/dashboard/account-wechat` |
| 修改密码 | `/dashboard/account-password` |
| 我的会话 | `/dashboard/account-sessions` |

角色预览和微信分类沿用账号能力的显示条件。角色预览生效期间，账号安全和微信分类禁用；分类导航不授予权限，服务端权限、会话和角色预览限制继续生效。旧个人资料 URL `/dashboard/account-profile` 跳转到 `/dashboard/account-security`；账号安全内容使用 `/dashboard/account-authentication-security`。

## 布局与行为

桌面使用左侧分类导航和右侧内容，内容区只展示一个页面标题。个人资料采用表单和头像两列，头像仍使用账号已有图片和现有上传接口。详情与自定义字段继续读取服务端定义，切换资料页内的标签不清除其他标签尚未保存的字段。

窄屏将分类导航放到内容上方，并收拢表单、头像和操作区域。由于 Lumira 分类多于参考页面，导航应避免占用过多首屏空间；此适配用于保持正文和操作可用，不改变主应用的移动导航。

个人资料页只负责资料和头像编辑，保存后留在当前页面并刷新账号信息。账号安全页集中提供邮箱、手机、通行密钥、安全记录和认证策略入口；原有独立弹层及其接口行为保持不变，关闭弹层返回账号安全页。修改密码成功后注销当前用户的已有会话；会话列表中的注销操作要求确认。

验收需覆盖桌面与窄屏的布局、分类导航、浏览器刷新和前后退、资料标签切换与嵌套弹层、键盘操作、浅色和深色主题。临时截图、检查报告和日志存放在 `.runtime`。
