import type { FrontendModule } from "@/platform/modules/types";
import { directoryManifest } from "./manifest";
import { lazy, type ReactNode } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";
import { isRolePreviewReadOnly } from "@/platform/api/client";
import { routeTabsForGroup } from "@/platform/modules/route-tabs";

const OnlineUsersPage = lazy(() => import("@/features/users/online-users-page").then(({ OnlineUsersPage }) => ({ default: OnlineUsersPage })));
const OrganizationsPage = lazy(() => import("@/features/organizations/organizations-page").then(({ OrganizationsPage }) => ({ default: OrganizationsPage })));
const RolesPage = lazy(() => import("@/features/roles/roles-page").then(({ RolesPage }) => ({ default: RolesPage })));
const UsersPage = lazy(() => import("@/features/users/users-page").then(({ UsersPage }) => ({ default: UsersPage })));

function UserManagementTabs({ authorities, hasGlobal, readOnlyPreview = false }: { authorities: string[]; hasGlobal: (permission: string) => boolean; readOnlyPreview?: boolean }) {
  const previewReadOnly = readOnlyPreview || isRolePreviewReadOnly();
  const childrenByRoute: Record<string, ReactNode | undefined> = {};
  if (authorities.includes("platform:users:read")) {
    childrenByRoute.users = <UsersPage
        readOnlyPreview={previewReadOnly}
        canCreate={!previewReadOnly && hasGlobal("platform:users:write")}
        canAssignOrganizations={!previewReadOnly && hasGlobal("platform:organizations:write") && authorities.includes("platform:organizations:read")}
        canAssignRoles={!previewReadOnly && hasGlobal("platform:roles:write") && authorities.includes("platform:roles:read")}
        canWrite={!previewReadOnly && authorities.includes("platform:users:write")}
      />;
  }
  if (!previewReadOnly && hasGlobal("platform:users:read")) {
    childrenByRoute["online-users"] = <OnlineUsersPage canWrite={hasGlobal("platform:users:write")} />;
  }
  return <RouteTabs tabs={routeTabsForGroup(directoryManifest, "user-activity", childrenByRoute)} />;
}

export const directoryFrontendModule = {
  ...directoryManifest,
  pages: {
    organizations: ({ hasGlobal, readOnlyPreview }) => <OrganizationsPage canWrite={hasGlobal("platform:organizations:write")} readOnlyPreview={readOnlyPreview} />,
    roles: ({ hasGlobal, authorities, readOnlyPreview }) => <RolesPage
      canWrite={hasGlobal("platform:roles:write")}
      authorities={authorities}
      canManageAuthenticationPolicy={hasGlobal("platform:roles:read") && hasGlobal("platform:roles:write") && hasGlobal("platform:settings:read") && hasGlobal("platform:settings:write")}
      canReadRegistrationSettings={!readOnlyPreview && authorities.includes("platform:settings:read")}
      readOnlyPreview={readOnlyPreview}
    />,
    users: ({ hasGlobal, authorities, readOnlyPreview }) => <UserManagementTabs authorities={authorities} hasGlobal={hasGlobal} readOnlyPreview={readOnlyPreview} />,
    "online-users": ({ hasGlobal, authorities, readOnlyPreview }) => <UserManagementTabs authorities={authorities} hasGlobal={hasGlobal} readOnlyPreview={readOnlyPreview} />,
  },
} satisfies FrontendModule;
