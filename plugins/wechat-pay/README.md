# WeChat Pay Native plugin

`wechat-pay` is an optional Lumira payment provider plugin. It implements the payment-core `PaymentGatewayPort` with the WeChat Pay APIv3 Native pre-order, order query/close, full refund/refund query, and signed payment-notification verification. Orders, encrypted settings, idempotency, callback routing, and state transitions belong to `payment-core`.

The plugin uses Java's built-in HTTP and cryptography APIs and the host's Jackson runtime. It does not include a WeChat payment SDK, use a configurable API origin, follow redirects, or perform calls during plugin startup. Requests go only to `https://api.mch.weixin.qq.com`, with a three-second connection timeout, an eight-second total response deadline, and bounded response bodies.

## Settings

Configure the following fields in the WeChat Pay settings page. The settings API never returns the PEM or APIv3 key; key fields show only whether a value is configured. Leaving an existing key blank keeps the current encrypted value.

| Field | Storage | Purpose |
| --- | --- | --- |
| `appId` | value | WeChat app ID enabled for Native QR payments |
| `merchantId` | value | Merchant ID (`mchid`) |
| `merchantSerialNumber` | value | Hexadecimal serial number for the merchant API certificate/private key |
| `wechatPayPublicKeyId` | value | `PUB_KEY_ID_…` identifier selected in WeChat Pay public-key mode |
| `notifyUrl` | value | Public HTTPS site URL ending in `/api/plugins/payment-core/callbacks/wechat-pay` |
| `merchantPrivateKeyPem` | encrypted secret | Merchant RSA private key in PKCS#8 or PKCS#1 PEM form |
| `wechatPayPublicKeyPem` | encrypted secret | WeChat Pay platform RSA public key associated with the configured public-key ID |
| `apiV3Key` | encrypted secret | Exactly 32 UTF-8 bytes used to authenticate/decrypt callback resources |

Both RSA keys must be at least 2048 bits. The merchant private key and WeChat Pay public key belong to different parties and are validated independently; they are not expected to be the same key pair. The configured callback URL must use HTTPS, have no user info/query/fragment, and use the exact payment-core callback path.

Payment amounts are positive integer fen values within the WeChat API's signed 32-bit amount range. The adapter only reports a paid order or successful refund after its signed provider response has the expected merchant/app/order identity, CNY currency, and amount. For a refund, it first verifies that the order is paid and the requested amount equals the complete paid amount.

## Protocol references

The implementation follows the official WeChat Pay APIv3 documentation:

- [Native pre-order](https://pay.wechatpay.cn/doc/v3/merchant/4012525171)
- [Query by merchant order number](https://pay.wechatpay.cn/doc/v3/merchant/4012526919)
- [Close order](https://pay.wechatpay.cn/doc/v3/merchant/4012526915)
- [Domestic refund](https://pay.wechatpay.cn/doc/v3/merchant/4012791862)
- [Callback signature verification and public-key mode](https://pay.wechatpay.cn/doc/v3/merchant/4012647435)
- [APIv3 callback resource decryption](https://pay.wechatpay.cn/doc/v3/partner/4012082320)
- [APIv3 request/response signing](https://pay.wechatpay.cn/doc/v3/partner/4012365880)

Tests generate synthetic RSA key pairs, signed callback bodies, and AES-GCM resources in memory. They do not use a merchant account or contact WeChat Pay.
