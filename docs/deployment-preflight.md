# 部署前只读配置预检

在运维方已通过受控方式准备标准 Compose 环境变量的终端运行：

```sh
python3 scripts/deployment-preflight.py
```

脚本只读取进程环境；不读取 `.env`、不生成/保存/更改凭据、不连接数据库或外部服务、不启动容器、不输出配置值。不要把秘密作为命令行参数或提交到仓库。

检查必填字段、三数据库密码互异、Office/可选指标令牌长度、规范32字节Base64钥环及active ID、生产安全cookie，以及固定代理IP与动态地址池的关系。退出0仅表示这些配置规则通过；任何错误退出1，输出仅字段名与固定规则。未知命令行参数退出2。

该工具严格面向仓库标准Compose。使用override、编排平台或外部数据库时，必须另行核对实际渲染配置。不能用这一步替代：
- TLS终结、可信代理、真实域名与浏览器cookie传输
- 数据库有效角色、迁移历史权限及旧卷升级
- 实际镜像摘要、批准插件图和制品一致性
- 备份/密钥分离保管、现场恢复与外部供应商受理

脚本不判断密码随机性、不证明持有原解密钥、不验证IP与现场网络是否冲突，也不会关闭运行时安全校验。CLI报告保留上述现场门槛，不能将`configuration-valid`写成生产已就绪。

变更 active 或移除旧钥前，必须另查当前库的加密及HMAC引用。保留旧钥只保证部分加密/多钥验证路径，不能保证所有在途挑战无感切换；本工具不读取当前库，不批准轮换。

密钥变更前另见 [只读密钥库存](key-inventory.md)。预检中的格式正确不能解除 active 切换或旧钥移除的在途 HMAC/备份阻断。Before changing keys, use the separate inventory; valid key formatting does not establish safe rotation or retirement.
