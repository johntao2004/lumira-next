# 支付插件

支付由三个独立插件组成。`payment-core` 保存渠道配置快照、订单和退款状态；`wechat-pay` 与 `alipay-pay` 分别实现稳定 SDK 接口 `com.lumira.plugin.sdk.common.PaymentGatewayPort`。两个渠道插件依赖 `platform` 和 `payment-core`，不依赖彼此。按需导入并启用核心插件和渠道插件即可；停用或卸载渠道会让其网关调用及回调授权失效。详细安装包操作见[插件运行说明](plugins.md)。

## 安装与开始使用

构建后的微信和支付宝渠道 ZIP 均附带相同版本的 `payment-core` 离线依赖，无需另行下载核心插件。在“平台管理 → 平台模块”导入对应渠道包，查看依赖安装计划后安装；需要两个渠道时依次导入、安装两个包，共用同一个支付核心。

安装包分别位于 `plugins/wechat-pay/dist/wechat-pay-1.0.5.lumira-plugin.zip` 和 `plugins/alipay-pay/dist/alipay-pay-1.0.5.lumira-plugin.zip`。首次部署本次新增的 SDK 和签名回调授权能力时，先部署配套宿主版本，再导入插件；之后插件生命周期操作可以在线执行。

安装后，在“平台管理”的微信支付或支付宝支付页面填写商户配置和密钥，在“运营中心”的对应订单入口创建二维码、查询订单、关闭未支付订单或发起全额退款。密钥输入框留空保留已保存的密钥。支付宝测试请选择 `SANDBOX` 并填写沙箱账号配置；正式收款请选择 `PRODUCTION`。

## 支付范围

首版面向已登录管理人员在网页工作台创建微信支付 Native 扫码单或支付宝当面付预创建二维码，并在管理工作台查单、关单和发起全额退款。订单使用人民币整数分保存。该版本不含移动 App SDK、JSAPI、小程序下单、部分退款或支付机构退款回调；在途退款通过渠道查单及核心插件的后台对账恢复。

真实商户接入前，商户须自行完成相应支付平台资质、应用、密钥/证书、收款权限和回调域名配置。浏览器展示二维码只信任渠道对应的 `weixin://` 或官方支付宝 HTTPS 二维码地址。生产回调 URL 必须为外网可达的 HTTPS 地址，且路径精确匹配该渠道：

```text
https://<your-public-host>/api/plugins/payment-core/callbacks/wechat-pay
https://<your-public-host>/api/plugins/payment-core/callbacks/alipay-pay
```

这些地址需要在渠道设置的 `notifyUrl` 中完整填写。支付平台必须能直接访问它们；不能填带查询参数、片段、用户名或非 HTTPS 的地址。

## 页面与操作权限

支付权限不按租户或组织拆分；页面访问和每项操作都须单独授予全局权限。每个渠道的设置页与订单页使用各自的访问权限和操作权限，因此微信设置、微信订单、支付宝设置、支付宝订单和核心全渠道订单可以分别授权。执行操作时，API 与服务层都要求同时拥有该页访问权限和对应操作权限。

| 页面 | 访问权限 | 操作权限 |
| --- | --- | --- |
| 全渠道订单（payment-core） | `platform:payment-core:orders-access` | `orders-read`, `orders-create`, `orders-manage`, `orders-refund` |
| 微信订单 | `platform:wechat-pay:orders-access` | `orders-read`, `orders-create`, `orders-manage`, `orders-refund` |
| 微信设置 | `platform:wechat-pay:settings-access` | `settings-read`, `settings-update` |
| 支付宝订单 | `platform:alipay-pay:orders-access` | `orders-read`, `orders-create`, `orders-manage`, `orders-refund` |
| 支付宝设置 | `platform:alipay-pay:settings-access` | `settings-read`, `settings-update` |

操作权限使用表中页面对应的插件前缀，例如微信退款为 `platform:wechat-pay:orders-refund`。核心订单页可以按筛选查看所有渠道；各渠道页固定绑定自己的 provider，不能通过请求参数、订单 ID 或页面请求头切换到另一渠道。

扫码回调不使用管理员会话。插件清单只对上述两个精确 POST 路由标记 `signedCallback: true`；宿主仅在对应插件已安装并启用时免除该请求的登录和 CSRF 检查。渠道适配器仍须验证原始通知签名、商户身份、订单号、金额和币种。其他公开接口仍仅允许已声明的 GET，渠道 POST 不能使用通配路径。

## HTTP 与 JSON 契约

全渠道订单 API 前缀为 `/api/plugins/payment-core`；渠道设置与订单 API 分别使用 `/api/plugins/wechat-pay` 和 `/api/plugins/alipay-pay`。以下请求体字段名固定，金额单位为分；订单和渠道交易状态采用大写枚举字符串。权限检查在 API 与服务层再次执行。

| 方法与路径 | 权限 | 用途 |
| --- | --- | --- |
| `GET /settings`（渠道前缀） | `settings-read` | 返回该渠道配置版本、非敏感 `values`、`configured` 以及只表示密钥是否存在的 `secrets` 布尔 map |
| `PUT /settings`（渠道前缀） | `settings-update` | 乐观锁保存该渠道配置和新密钥 |
| `GET /orders?providerId=&status=&page=1&size=20` | `read` | 分页订单；省略 providerId 查询所有渠道 |
| `POST /orders`（核心或渠道前缀） | `orders-create` | 创建扫码单；渠道前缀固定支付渠道 |
| `POST /orders/{orderId}/query`（核心或渠道前缀） | `orders-manage` | 从渠道查单并恢复本地状态 |
| `POST /orders/{orderId}/close`（核心或渠道前缀） | `orders-manage` | 请求渠道关单 |
| `POST /orders/{orderId}/refund`（核心或渠道前缀） | `orders-refund` | 发起全额退款 |
| `POST /orders/{orderId}/refund/query`（核心或渠道前缀） | `orders-refund` | 查询并恢复退款状态 |
| `POST /callbacks/wechat-pay` | 渠道签名验证 | 微信支付通知原始报文；验证成功返回 HTTP 204 |
| `POST /callbacks/alipay-pay` | 渠道签名验证 | 支付宝表单通知原始报文；验证成功返回纯文本 `success` |

创建请求示例：

```json
{
  "idempotencyKey": "4a0fd9af-86d6-4c85-bda0-f531d8df18d2",
  "providerId": "wechat-pay",
  "amountFen": 1250,
  "subject": "商品说明"
}
```

同一幂等 UUID 与相同 provider、金额和商品说明重复提交，会返回原订单且不会再次调用预下单；同一 UUID 搭配不同内容返回 HTTP 409。商户订单号在预下单前持久化，网络超时会保留 `UNKNOWN` 与同一订单号。调用方应使用“查询订单”恢复远端结果，不能因创建超时而创建新幂等键并假设原单未下单。

设置更新使用 `expectedVersion`，版本过期返回 HTTP 409。配置响应不会回显密钥，仅显示类似 `{"appPrivateKey":true}` 的存在标志。密钥只能通过 `secrets` 提交；渠道声明的 secret 字段如果混入 `values` 或提交未知密钥名会被拒绝。校验错误使用稳定通用错误码，不返回渠道响应、私钥或请求报文。

## 订单与退款状态

订单状态为 `CREATING`、`PENDING`、`UNKNOWN`、`PAID`、`CLOSED`、`FAILED`、`REFUND_PENDING` 或 `REFUNDED`。仅经平台查询或签名通知验证、商户单号匹配、本地渠道一致且 CNY 金额与冻结金额完全相同后，才会记为 `PAID`。支付成功是单向状态：普通待支付、失败或关单查单不能让已支付订单回退；迟到且经过验证的支付成功通知优先于本地关单/失败状态。已全额退款的订单也不会被重复支付通知改回已支付。

退款只有全额。核心在发起渠道调用前持久化退款幂等键和固定的 `outRefundNo`。相同键和相同原因再次提交只查原退款，不会再发起退款；同一键变更内容冲突。远端明确拒绝后可使用新的幂等键重新发起；网络超时会标为待恢复，并沿用原退款号。只有渠道查单确认成功且退款金额精确匹配订单全额时才标为 `REFUNDED`。当前没有退款异步通知处理器；插件每 30 秒至多处理 20 个待恢复订单/退款，页面也提供手动查单和查退款。

对账任务不在持有数据库事务期间发起网络调用。一次调用异常不会将订单误记为失败或已支付；管理员可在订单工作台手动查询，后台任务会持续处理 CREATING、PENDING、UNKNOWN 与 REFUND_PENDING 记录。渠道响应中的原始错误文本不透传为 API 错误。

## 配置密钥与版本轮换

非敏感配置进入支付核心配置表和只增的配置历史；每个订单固定其 `settingsVersion`，以后查单和退款继续使用这份配置。渠道密钥以加密 `SecretStorePort` 保存，在插件数据集中只允许两个精确上下文 `plugin:payment-core/wechat-pay` 和 `plugin:payment-core/alipay-pay`。平台密钥环负责加密，API、日志和 SDK `Settings.toString()` 不输出明文密钥。卸载并选择删除插件数据时，宿主只清理这两个支付核心上下文；普通保留数据卸载不会删除凭据或订单。

配置轮换会保留仍被未终态、已支付/待退款或最近七天订单引用的历史 secret 快照。平台 SecretStore 单上下文允许的加密数据上限为 64 KiB；支付核心将一个渠道历史快照 vault 限制在 48 KiB，以预留加密封套空间。达到该限制时配置保存会明确失败，不会丢失当前配置。长期未退款的已支付订单继续引用原配置快照，因此清理旧凭据前应先完成业务退款与对账。

回调按有界历史配置逐个验签，验签后还会核对本地订单的渠道和该订单冻结的配置版本；找不到匹配版本或候选快照超过上限时会失败关闭并让支付平台重试。微信 API v3 加密通知可能需先解密才能得到商户订单号，所以不能只用当前密钥验证历史订单通知。

## 验收边界

自动测试只使用合成凭据、合成二维码地址、合成订单号和签名夹具，不连接真实商户账号，也不发起真实扣款或退款。当前代码验证不代表微信支付或支付宝生产商户已完成联调；正式上线仍需在商户沙箱/受控测试商户验证平台证书轮换、网络重试、回调可达性、真实扫码与退款状态，并由业务负责人确认风险与资金流程。

## 退款受理与终态

支付宝 `code=10000` 只表示请求成功；退款响应 `fund_change=Y` 才立即确认成功。`N` 或缺少此字段继续以原 `out_request_no` 查询，只有明确成功状态及匹配金额才结单。未知标记拒绝解析，保留核心待核实状态。

微信 `PROCESSING`、`ABNORMAL` 均保留待处理，继续追踪同一退款号；`ABNORMAL` 仍可能处理为成功，不能标失败后再建另一退款。`SUCCESS`、`CLOSED` 才分别映射成功/失败。待处理期间核心拒绝替换退款幂等键。

依据：[支付宝退款注意事项](https://aipay.alipay.com/docs/subscribe/api-list/other-basic-interfaces/alipay-trade-refund.html)、[微信退款状态](https://pay.wechatpay.cn/doc/v3/merchant/4013071031)。回归使用独立商户/平台测试密钥、真实回环HTTPS和签名应答及独立PostgreSQL；不代表真实商户受理、资金变动或对账验收。

微信普通查单的 `REFUND` 表示进入退款流程，并不证明退款完成；当前适配器保守拒绝将其转换为PAID或REFUNDED。本地已记录的REFUND_PENDING由原退款号查询继续协调。商户后台发起而本地没有退款号的退款，以及CLOSED后换新退款号重新申请，不属于当前已验证的自动协调路径，须先人工核对官方交易/退款记录，不能靠重复点击或更换幂等键绕过。
