/** The backend may return only the fixed website QR authorization endpoint. */
export function isTrustedWechatAuthorizationUrl(value: string) {
  try {
    const url = new URL(value);
    return url.protocol === "https:"
      && url.hostname === "open.weixin.qq.com"
      && !url.username && !url.password
      && (url.port === "" || url.port === "443")
      && url.pathname === "/connect/qrconnect";
  } catch {
    return false;
  }
}
