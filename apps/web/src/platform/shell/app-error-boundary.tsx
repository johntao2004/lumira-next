import { Component, type ErrorInfo, type ReactNode } from "react";
import { Button, Descriptions, Result, Space, theme } from "antd";
import { resolveLocale, translate, type MessageKey } from "../i18n/messages";
import { describeAppError, errorComponent, type AppErrorDiagnostic } from "./app-error-diagnostics";

type BoundaryProps = {
  children: ReactNode;
  locale: string;
  t: (key: MessageKey) => string;
};

const reasons: Record<AppErrorDiagnostic["kind"], MessageKey> = {
  chunk: "pageErrorChunkReason",
  asset: "pageErrorAssetReason",
  network: "pageErrorNetworkReason",
  http: "pageErrorHttpReason",
  render: "pageErrorRenderReason",
};

function ErrorFallback({ diagnostic, locale, t }: Omit<BoundaryProps, "children"> & { diagnostic: AppErrorDiagnostic }) {
  const { token } = theme.useToken();
  const label = (key: MessageKey) => t(key).trim() || translate(resolveLocale(locale), key);
  return (
    // The failing chunk may also contain global CSS. Keep the recovery surface
    // readable using the provider's tokens without depending on that stylesheet.
    <main className="app-error-page" style={{ minHeight: "100dvh", padding: "clamp(16px, 4vw, 48px)", background: token.colorBgLayout, color: token.colorText }}>
      <Result
        status="error"
        title={label("pageError")}
        subTitle={label(reasons[diagnostic.kind])}
        style={{ maxWidth: 800, marginInline: "auto", paddingInline: 0 }}
        styles={{ body: { background: "transparent", padding: 0, textAlign: "start", overflowWrap: "anywhere" } }}
        extra={<Space wrap>
          <Button type="primary" onClick={() => window.location.reload()}>{label("retry")}</Button>
          <Button href="/dashboard/home">{label("backHome")}</Button>
        </Space>}
      >
          <Descriptions column={1} size="small" styles={{ label: { color: token.colorTextSecondary }, content: { color: token.colorText, minWidth: 0, overflowWrap: "anywhere" } }} items={[
            { key: "number", label: label("pageErrorNumber"), children: <code>{diagnostic.number}</code> },
            { key: "code", label: label("pageErrorCode"), children: <code>{diagnostic.code}</code> },
            { key: "page", label: label("pageErrorLocation"), children: diagnostic.page },
            { key: "resource", label: label("pageErrorResource"), children: diagnostic.resource ?? (diagnostic.component || label("pageErrorPageComponent")) },
            { key: "type", label: label("pageErrorType"), children: diagnostic.errorType },
            { key: "detail", label: label("pageErrorDetail"), children: diagnostic.detail || label("pageErrorNoDetail") },
            { key: "time", label: label("pageErrorTime"), children: <time dateTime={diagnostic.occurredAt}>{new Date(diagnostic.occurredAt).toLocaleString(locale)}</time> },
          ]} />
      </Result>
    </main>
  );
}

export class AppErrorBoundary extends Component<
  BoundaryProps,
  { diagnostic: AppErrorDiagnostic | null }
> {
  state: { diagnostic: AppErrorDiagnostic | null } = { diagnostic: null };

  static getDerivedStateFromError(error: unknown) {
    return { diagnostic: describeAppError(error, window.location.pathname) };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    const diagnostic = this.state.diagnostic;
    if (!diagnostic) return;
    this.setState({ diagnostic: { ...diagnostic, component: errorComponent(info.componentStack) } });
    console.error("Uncaught application error", diagnostic.number, diagnostic.code, error, info.componentStack);
  }

  render() {
    return this.state.diagnostic ? (
      <ErrorFallback {...this.props} diagnostic={this.state.diagnostic} />
    ) : (
      this.props.children
    );
  }
}
