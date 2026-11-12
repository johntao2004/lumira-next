import type { FrontendModule } from "@/platform/modules/types";
import { directoryManifest } from "./manifest";
import { lazy, type ReactNode } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";
import { isRolePreviewReadOnly } from "@/platform/api/client";
import { routeTabsForGroup } from "@/platform/modules/route-tabs";

const OnlineUsersPage = lazy(() => import("./users/online-users-page").then(({ OnlineUsersPage }) => ({ default: OnlineUsersPage })));
const OrganizationsPage = lazy(() => import("./organizations/organizations-page").then(({ OrganizationsPage }) => ({ default: OrganizationsPage })));
const RolesPage = lazy(() => import("./roles/roles-page").then(({ RolesPage }) => ({ default: RolesPage })));
const UsersPage = lazy(() => import("./users/users-page").then(({ UsersPage }) => ({ default: UsersPage })));
const PrintTemplatesPage = lazy(() => import("./print-templates/print-templates-page").then(({ PrintTemplatesPage }) => ({ default: PrintTemplatesPage })));

function UserManagementTabs({ hasRouteAccess, hasRouteOperation, readOnlyPreview = false }: { hasRouteAccess: (routeId: string) => boolean; hasRouteOperation: (routeId: string, actionId: string) => boolean; readOnlyPreview?: boolean }) {
  const previewReadOnly = readOnlyPreview || isRolePreviewReadOnly();
  const childrenByRoute: Record<string, ReactNode | undefined> = {};
  if (hasRouteAccess("users")) {
    childrenByRoute.users = <UsersPage
        readOnlyPreview={previewReadOnly}
        canCreate={hasRouteOperation("users", "create")}
        canUpdate={hasRouteOperation("users", "update")}
        canDelete={hasRouteOperation("users", "delete")}
        canResetPassword={hasRouteOperation("users", "resetPassword")}
        canAssignOrganizations={hasRouteOperation("users", "assignOrganizations")}
        canAssignRoles={hasRouteOperation("users", "assignRoles")}
        canReadRoles={hasRouteOperation("users", "readRoles") || hasRouteOperation("users", "assignRoles")}
        canExport={hasRouteOperation("users", "export")}
        canImport={hasRouteOperation("users", "import")}
        canPrint={hasRouteOperation("users", "print")}
        canUseTemplates={hasRouteAccess("print-templates") && hasRouteOperation("print-templates", "use")}
      />;
  }
  if (!previewReadOnly && hasRouteAccess("online-users")) {
    childrenByRoute["online-users"] = <OnlineUsersPage canTerminateSessions={hasRouteOperation("online-users", "terminateSession")} />;
  }
  return <RouteTabs tabs={routeTabsForGroup(directoryManifest, "user-activity", childrenByRoute)} />;
}

export const directoryFrontendModule = {
  ...directoryManifest,
  pages: {
    organizations: ({ hasRouteOperation, readOnlyPreview }) => <OrganizationsPage
      canCreate={hasRouteOperation("organizations", "create")}
      canUpdate={hasRouteOperation("organizations", "update")}
      canDelete={hasRouteOperation("organizations", "delete")}
      readOnlyPreview={readOnlyPreview}
    />,
    roles: ({ hasGlobal, hasRouteOperation, hasRouteAccess, authorities, readOnlyPreview }) => <RolesPage
      canCreate={hasRouteOperation("roles", "create")}
      canUpdate={hasRouteOperation("roles", "update")}
      canDelete={hasRouteOperation("roles", "delete")}
      canManagePluginPermissions={hasRouteOperation("modules", "install")}
      authorities={authorities.filter((permission) => hasGlobal(permission))}
      canReadAuthenticationPolicy={hasRouteOperation("roles", "readAuthenticationPolicy")}
      canManageAuthenticationPolicy={hasRouteOperation("roles", "updateAuthenticationPolicy")}
      canReadRegistrationSettings={!readOnlyPreview && hasRouteAccess("registration")}
      canReadFileQuota={!readOnlyPreview && hasRouteAccess("files-all")}
      readOnlyPreview={readOnlyPreview}
    />,
    users: ({ hasRouteAccess, hasRouteOperation, readOnlyPreview }) => <UserManagementTabs hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} readOnlyPreview={readOnlyPreview} />,
    "online-users": ({ hasRouteAccess, hasRouteOperation, readOnlyPreview }) => <UserManagementTabs hasRouteAccess={hasRouteAccess} hasRouteOperation={hasRouteOperation} readOnlyPreview={readOnlyPreview} />,
    "print-templates": ({ hasRouteAccess, hasRouteOperation, readOnlyPreview }) => <PrintTemplatesPage
      canCreate={hasRouteOperation("print-templates", "create")}
      canUpdate={hasRouteOperation("print-templates", "update")}
      canPublish={hasRouteOperation("print-templates", "publish")}
      canUse={hasRouteOperation("print-templates", "use")}
      canReadExampleUsers={hasRouteAccess("users")}
      readOnlyPreview={readOnlyPreview}
    />,
  },
} satisfies FrontendModule;
