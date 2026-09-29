import type { FrontendModule } from "@/platform/modules/types";
import { directoryManifest } from "./manifest";
import { lazy } from "react";
import { RouteTabs } from "@/platform/components/route-tabs";

const OnlineUsersPage = lazy(() => import("@/features/users/online-users-page").then(({ OnlineUsersPage }) => ({ default: OnlineUsersPage })));
const OrganizationsPage = lazy(() => import("@/features/organizations/organizations-page").then(({ OrganizationsPage }) => ({ default: OrganizationsPage })));
const RolesPage = lazy(() => import("@/features/roles/roles-page").then(({ RolesPage }) => ({ default: RolesPage })));
const UsersPage = lazy(() => import("@/features/users/users-page").then(({ UsersPage }) => ({ default: UsersPage })));

function UserManagementTabs({ authorities, hasGlobal }: { authorities: string[]; hasGlobal: (permission: string) => boolean }) {
  const tabs = [];
  if (authorities.includes("platform:users:read")) {
    tabs.push({
      key: "users",
      path: "/dashboard/users",
      label: "userList" as const,
      children: <UsersPage
        canCreate={hasGlobal("platform:users:write")}
        canAssignOrganizations={hasGlobal("platform:organizations:write") && authorities.includes("platform:organizations:read")}
        canAssignRoles={hasGlobal("platform:roles:write") && authorities.includes("platform:roles:read")}
        canWrite={authorities.includes("platform:users:write")}
      />,
    });
  }
  if (hasGlobal("platform:users:read")) {
    tabs.push({
      key: "online-users",
      path: "/dashboard/online-users",
      label: "onlineUsers" as const,
      children: <OnlineUsersPage canWrite={hasGlobal("platform:users:write")} />,
    });
  }
  return <RouteTabs tabs={tabs} />;
}

export const directoryFrontendModule = {
  ...directoryManifest,
  pages: {
    organizations: ({ hasGlobal }) => <OrganizationsPage canWrite={hasGlobal("platform:organizations:write")} />,
    roles: ({ hasGlobal, authorities }) => <RolesPage canWrite={hasGlobal("platform:roles:write")} authorities={authorities} />,
    users: ({ hasGlobal, authorities }) => <UserManagementTabs authorities={authorities} hasGlobal={hasGlobal} />,
    "online-users": ({ hasGlobal, authorities }) => <UserManagementTabs authorities={authorities} hasGlobal={hasGlobal} />,
  },
} satisfies FrontendModule;
