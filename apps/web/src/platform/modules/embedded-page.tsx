"use client";
import { useSyncExternalStore } from "react";
import { frontendRouteRegistrationKey, renderFrontendPage, subscribeFrontendWorkspaceActions } from "./registry";
import type { FrontendPageContext } from "./types";
/** Plugin route composition follows registration and authorization, including unload/replacement. */
export function EmbeddedFrontendPage({ routeId, context, onSaved }: { routeId: string; context: FrontendPageContext; onSaved?: () => void | Promise<void> }) {
  const registration = useSyncExternalStore(
    subscribeFrontendWorkspaceActions,
    () => frontendRouteRegistrationKey(routeId),
    () => null,
  );
  if (!registration || !context.hasRouteAccess(routeId)) return null;
  return <div key={`${registration}:${routeId}`}>{renderFrontendPage(routeId, {
    ...context,
    embedded: { ...context.embedded, onSaved: onSaved ?? context.embedded?.onSaved },
  })}</div>;
}
