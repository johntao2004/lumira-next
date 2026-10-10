# 弥散渐变头像

`diffuse-avatar` 为启用的 Lumira 账号生成稳定的弥散渐变头像。宿主按账号 UUID 调用 `GeneratedAvatarPort`；同一 UUID 在同一插件版本中总会得到相同的 256 × 256 PNG。图像由 Java 标准库在内存中绘制，不调用外部服务，不查询或持久化账号资料，也不创建后台线程。每个 UUID 通过连续色相、明度和浓度采样获得独立配色；颜色空间使用 OKLCH，超出 sRGB 色域时固定明度和色相、降低浓度映射。

插件依赖 `identity@1.0.0` 和 `files@1.0.0`，不注册业务路由、权限或前端页面。停用插件后宿主不会再发现它提供的头像扩展。

在仓库根目录构建插件：

```sh
pnpm plugins:build diffuse-avatar
```

专用确定性图像测试：

```sh
backend/mvnw -f backend/pom.xml '-Dtest=DiffuseAvatar*Test' test
```

安装包位于 `plugins/diffuse-avatar/dist/diffuse-avatar-1.0.1.lumira-plugin.zip`。

从 1.0.0 升级到 1.0.1 时，自动生成的头像会切换到新配色；升级后同一账号每次刷新仍保持一致。用户上传并保存的头像保持不变。
