# 支付宝扫码支付

`alipay-pay` is an optional payment-channel adapter for Alipay Face-to-Face QR
payments. It creates QR payment orders with `alipay.trade.precreate`, queries
and closes trades, requests full refunds, queries refund results, and verifies
Alipay asynchronous form notifications. Order persistence, encrypted secrets,
permissions, idempotency, callbacks, and refunds remain owned by the
`payment-core` plugin.

## Configuration

Configure the provider through the payment-core settings page. Values are:

- `environment`: explicitly choose `SANDBOX` or `PRODUCTION`.
- `appId`: the app ID paired with the selected environment.
- `sellerId`: the expected Alipay seller ID; notifications must match it.
- `notifyUrl`: the public HTTPS callback URL, including the exact
  `/api/plugins/payment-core/callbacks/alipay-pay` path.

Secrets are `appPrivateKey` (the merchant application's RSA private key) and
`alipayPublicKey` (the RSA public key published by Alipay). Paste either
PKCS#8 or PKCS#1 private PEM and an X.509 public-key PEM. Both keys must be RSA
and at least 2048 bits (payment-core limits each secret to 8192 characters).
These are intentionally different keys: requests are
signed with the merchant key, while gateway responses and notifications are
verified with Alipay's platform key. Secret values are stored by payment-core
and are never returned by its settings API.

The adapter does not accept a gateway URL. Production always uses
`https://openapi.alipay.com/gateway.do`; sandbox always uses
`https://openapi-sandbox.dl.alipaydev.com/gateway.do`. Sandbox is an explicit environment
selection and must use its sandbox app ID, merchant private key, and Alipay
sandbox public key. HTTP requests use a
fixed HTTPS origin, bounded timeouts and response size, and do not follow
redirects.

The sandbox gateway host follows Alipay's [official sandbox integration example](https://aipay.alipay.com/developers/blog/gecmiv6v1q10i3pw),
which configures `https://openapi-sandbox.dl.alipaydev.com/gateway.do`.

## Signature handling

Outbound OpenAPI requests sort the non-empty parameters and sign all of them
except `sign`; `sign_type=RSA2` is included. This follows the official Java
SDK's [`AbstractAlipayClient`](https://github.com/alipay/alipay-sdk-java-all/blob/master/v2/src/main/java/com/alipay/api/AbstractAlipayClient.java#L728-L771)
and [`AlipaySignature`](https://github.com/alipay/alipay-sdk-java-all/blob/master/v2/src/main/java/com/alipay/api/internal/util/AlipaySignature.java#L254-L290)
request-signing flow. Async form notifications use their separate canonical
rule and exclude both `sign` and `sign_type`. Gateway JSON responses are
verified against the exact raw response object substring; the adapter never
re-serializes the parsed payload before verification.

## Build and install

Use Node 24, pnpm 10.33.0 and JDK 25. Build with:

```sh
pnpm plugins:build alipay-pay
```

The package is written to `plugins/alipay-pay/dist/alipay-pay-1.0.5.lumira-plugin.zip`.
Import and install it through the platform plugin manager after installing
`payment-core`.

Unit tests use generated local RSA keys and synthetic Alipay responses. They do
not contact an Alipay gateway or create/refund merchant transactions.
