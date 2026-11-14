import { Component, Suspense, useSyncExternalStore, type ReactNode } from "react";
import { frontendWorkspaceActions, subscribeFrontendWorkspaceActions } from "../modules/page-store";

/** A failed optional control must not take down the navigation or other modules. */
class WorkspaceActionBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? null : this.props.children;
  }
}

export function WorkspaceModuleActions() {
  const actions = useSyncExternalStore(
    subscribeFrontendWorkspaceActions,
    frontendWorkspaceActions,
    frontendWorkspaceActions,
  );
  return actions.map(({ key, component: Action }) => (
    <WorkspaceActionBoundary key={key}>
      <Suspense fallback={null}><Action /></Suspense>
    </WorkspaceActionBoundary>
  ));
}
