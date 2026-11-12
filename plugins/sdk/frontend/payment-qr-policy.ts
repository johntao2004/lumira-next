export type PaymentQrData = { providerId: string; codeUrl: string | null };

/** A channel may supply its own validator; every validator still shares the bounded text envelope. */
export function trustedPaymentQrUrl(order: PaymentQrData, validator?: (value: string) => boolean): string | null {
  const value = order.codeUrl;
  if (!value || value.length > 512 || /[\u0000-\u0020]/.test(value)) return null;
  try {
    if (validator) return validator(value) ? value : null;
    const url = new URL(value);
    if (order.providerId === "wechat-pay") {
      return url.protocol === "weixin:" && url.hostname === "wxpay" && !url.port && !url.username && !url.password
        && ["/bizpayurl", "/bizpayurl/up"].includes(url.pathname) && Boolean(url.search) && !url.hash ? value : null;
    }
    if (order.providerId === "alipay-pay") {
      return url.protocol === "https:" && url.hostname === "qr.alipay.com" && !url.username && !url.password ? value : null;
    }
    return null;
  } catch {
    return null;
  }
}
