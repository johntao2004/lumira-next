import { Component, type ErrorInfo, type ReactNode } from "react";
import { Button, Result } from "antd";

function ErrorFallback({ message, retryLabel }: { message: string; retryLabel: string }) {
  return (
    <main className="workspace-status">
      <Result
        status="500"
        title="500"
        subTitle={message}
        extra={
          <Button type="primary" onClick={() => window.location.reload()}>
            {retryLabel}
          </Button>
        }
      />
    </main>
  );
}

export class AppErrorBoundary extends Component<
  { children: ReactNode; message: string; retryLabel: string },
  { failed: boolean }
> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("Uncaught application error", error, info.componentStack);
  }

  render() {
    return this.state.failed ? (
      <ErrorFallback message={this.props.message} retryLabel={this.props.retryLabel} />
    ) : (
      this.props.children
    );
  }
}
