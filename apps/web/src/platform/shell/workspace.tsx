"use client";

import { DraftsDrawer } from "@/features/account/drafts-drawer";
import { PasswordDrawer } from "@/features/account/password-drawer";
import { ProfileDrawer } from "@/features/account/profile-drawer";
import { SessionsDrawer } from "@/features/account/sessions-drawer";
import { NotificationsButton } from "@/features/notifications/notifications-button";
import { ApiError, api, logout } from "@/platform/api/client";
import type { CurrentUser } from "@/platform/auth/api-types";
import { PlatformWatermark } from "@/platform/branding/watermark";
import { useBranding } from "@/platform/branding/provider";
import { AccountAvatar } from "@/platform/components/account-avatar";
import { LanguageSwitcher } from "@/platform/i18n/language-switcher";
import type { ModuleInfo } from "@/platform/modules/api-types";
import { renderFrontendPage } from "@/platform/modules/registry";
import type { RuntimeNavigationNode } from "@/platform/navigation/runtime-types";
import { usePreferences } from "@/platform/providers";
import {
  PageContainer,
  ProCard,
  ProLayout,
  StatisticCard,
} from "@ant-design/pro-components";
import { AppstoreFilled, MoonOutlined, SunOutlined } from "@ant-design/icons";
import { history, Link, useLocation } from "@umijs/max";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { App, Button, Result, Skeleton, Space, Tag, Typography } from "antd";
import { Suspense, useEffect, useState } from "react";
import { AccountMenu } from "./account-menu";
import { DynamicPage } from "./dynamic-page";
import { useDisplayScale } from "../display/use-display-scale";
import { buildProLayoutRoute } from "./navigation";
import {
  availableNavigation,
  canAccessSection,
  collapseTabbedNavigation,
  isDashboardSection,
  isSection,
  localizedNavigationLabel,
  mergeRuntimeNavigation,
  navigationPageIdForRoute,
  routes,
  type Section,
} from "./routes";

export function Workspace({ children = null }: { children?: React.ReactNode }) {
  const { pathname } = useLocation();
  const displayScale = useDisplayScale();
  const section = pathname.split("/").filter(Boolean)[1] ?? "home";
  const branding = useBranding();
  const [profileOpen, setProfileOpen] = useState(false);
  const [sessionsOpen, setSessionsOpen] = useState(false);
  const [draftsOpen, setDraftsOpen] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [desktopCollapsed, setDesktopCollapsed] = useState(false);
  const [mobileCollapsed, setMobileCollapsed] = useState(true);
  const [mobileShell, setMobileShell] = useState(false);
  const client = useQueryClient();
  const { dark, toggleDark, t } = usePreferences();
  const { message } = App.useApp();
  const errorText = (error: unknown) =>
    t(error instanceof ApiError && error.status === 403 ? "forbidden" : "unavailable");

  const user = useQuery({
    queryKey: ["session"],
    staleTime: 0,
    refetchOnWindowFocus: true,
    refetchInterval: 30_000,
    queryFn: () => api<CurrentUser>("/auth/me"),
  });
  const canReadModules = user.data?.authorities.includes("platform:modules:read") ?? false;
  const modules = useQuery({
    queryKey: ["modules"],
    queryFn: () => api<ModuleInfo[]>("/platform/modules"),
    enabled: canReadModules,
  });
  const runtimeNavigation = useQuery({
    queryKey: ["navigation-runtime"],
    queryFn: ({ signal }) => api<RuntimeNavigationNode[]>("/platform/navigation", { signal }),
    enabled: Boolean(user.data),
    staleTime: 30_000,
  });
  const routeForTitle = isSection(section) ? routes[section] : undefined;
  const runtimePageForTitle = findRuntimePage(runtimeNavigation.data ?? [], section);
  const navigation = collapseTabbedNavigation(mergeRuntimeNavigation(
    availableNavigation(
      user.data?.authorities ?? [],
      user.data?.enabledModules ?? [],
      user.data?.globalAuthorities ?? [],
    ),
    runtimeNavigation.data,
  ));
  const navigationSection = navigationPageIdForRoute(section, navigation);
  const resolvedPageTitle = routeForTitle
    ? t(routeForTitle.label)
    : localizedNavigationLabel(runtimePageForTitle?.labelKey, runtimePageForTitle?.labelDefault, t) || t("notFound");

  useEffect(() => {
    document.title = `${resolvedPageTitle} - ${branding.websiteName}`;
  }, [branding.websiteName, resolvedPageTitle]);

  useEffect(() => {
    const refresh = () => {
      void client.invalidateQueries({ queryKey: ["session"] });
      void client.invalidateQueries({ queryKey: ["modules"] });
    };
    const channel = new BroadcastChannel("lumira:modules");
    channel.onmessage = refresh;
    window.addEventListener("lumira:modules-changed", refresh);
    return () => {
      channel.close();
      window.removeEventListener("lumira:modules-changed", refresh);
    };
  }, [client]);

  useEffect(() => {
    const mobile = window.matchMedia("(max-width: 767px)");
    const syncMobileShell = () => {
      setMobileShell(mobile.matches);
      setMobileCollapsed(true);
    };
    syncMobileShell();
    mobile.addEventListener("change", syncMobileShell);
    return () => {
      mobile.removeEventListener("change", syncMobileShell);
    };
  }, []);

  if (user.isPending) {
    return <main className="workspace-loading"><Skeleton active paragraph={{ rows: 10 }} /></main>;
  }
  if (!user.data) {
    return (
      <main className="workspace-status">
        <Result
          status={user.error instanceof ApiError && user.error.status === 403 ? "403" : "500"}
          title={t("accountError")}
          subTitle={errorText(user.error)}
          extra={<Button type="primary" onClick={() => void user.refetch()}>{t("retry")}</Button>}
        />
      </main>
    );
  }

  const accountActions = {
    displayName: user.data.displayName,
    onProfile: () => setProfileOpen(true),
    onSessions: () => setSessionsOpen(true),
    onDrafts: () => setDraftsOpen(true),
    onChangePassword: () => setPasswordOpen(true),
    onLogout: async () => {
      try {
        await logout();
        client.clear();
        history.replace("/user/login");
      } catch (error) {
        void message.error(errorText(error));
      }
    },
  };
  const hasGlobal = (permission: string) => user.data.globalAuthorities?.includes(permission) ?? false;
  const activeRoute = routeForTitle;
  const runtimePage = runtimePageForTitle;
  const moduleDenied = Boolean(activeRoute?.module && !(user.data.enabledModules ?? []).includes(activeRoute.module));
  const runtimeLoading = !activeRoute && runtimeNavigation.isPending;
  const denied = runtimeLoading
    ? false
    : activeRoute
      ? !canAccessSection(section as Section, user.data.authorities, user.data.enabledModules ?? [], user.data.globalAuthorities ?? [])
      : !runtimePage;
  const title = resolvedPageTitle;
  const featurePage = !denied && isSection(section) && section !== "home"
    ? renderFrontendPage(section, { authorities: user.data.authorities, hasGlobal })
    : null;
  const layoutRoute = buildProLayoutRoute(navigation, t);
  const activeGroup = navigation.find((group) =>
    group.pages.some((page) => page.id === navigationSection || page.path === pathname),
  );
  const activeMenuPage = navigation.flatMap((group) => group.pages).find((page) => page.id === navigationSection);
  const layoutPath = activeMenuPage?.path || (activeMenuPage ? `/dashboard/${activeMenuPage.id}` : pathname);
  const breadcrumbItems = [
    { title: <Link to="/dashboard/home">{t("home")}</Link> },
    ...(activeGroup && section !== "home"
      ? [{ title: localizedNavigationLabel(activeGroup.label, activeGroup.labelDefault, t) }]
      : []),
    ...(section !== "home" ? [{ title }] : []),
  ];
  const utilityActions = () => [
    <Button
      type="text"
      key="theme"
      icon={dark ? <SunOutlined /> : <MoonOutlined />}
      onClick={toggleDark}
      aria-label={t(dark ? "lightMode" : "darkMode")}
    />,
    <LanguageSwitcher key="language" />,
    <NotificationsButton key="notifications" />,
  ];
  const accountTrigger = (compact = false) => (
    // ProLayout reads aria-hidden as a request to skip the action-wrapper hover; AccountMenu does not forward it to the DOM.
    <AccountMenu {...accountActions} aria-hidden>
      <button type="button" className="pro-account-trigger" aria-label={t("account")}>
        <AccountAvatar size={displayScale.accountAvatarSize} />
        {!compact && <span>{user.data.displayName}</span>}
      </button>
    </AccountMenu>
  );
  const layoutCollapsed = mobileShell ? mobileCollapsed : desktopCollapsed;

  return (
    <PlatformWatermark>
      <ProfileDrawer open={profileOpen} onClose={() => setProfileOpen(false)} />
      <DraftsDrawer open={draftsOpen} onClose={() => setDraftsOpen(false)} />
      <SessionsDrawer open={sessionsOpen} onClose={() => setSessionsOpen(false)} />
      <PasswordDrawer open={passwordOpen} onClose={() => setPasswordOpen(false)} />
      <div className="workspace-shell">
        <ProLayout
          className="workspace"
          token={{
            bgLayout: null,
            header: {
              colorBgHeader: "var(--ant-color-bg-container)",
              colorBgScrollHeader: "var(--ant-color-bg-container)",
            },
            ...(dark ? {
              sider: {
                colorBgMenuItemSelected: "#1677ff",
                colorTextMenuSelected: "#fff",
                colorTextSubMenuSelected: "#fff",
              },
            } : {}),
          }}
          logo={branding.websiteLogoUrl
            ? <img src={branding.websiteLogoUrl} alt="" />
            : <AppstoreFilled className="workspace-brand-icon" />}
          title={branding.websiteName}
          onMenuHeaderClick={() => history.push("/dashboard/home")}
          actionsRender={() => [...utilityActions(), accountTrigger()]}
          route={layoutRoute}
          location={{ pathname: layoutPath }}
          layout="mix"
          navTheme={dark ? "realDark" : "light"}
          contentWidth="Fluid"
          fixedHeader
          fixSiderbar
          breakpoint={false}
          siderWidth={displayScale.siderWidth}
          menu={{ collapsedWidth: displayScale.collapsedSiderWidth, defaultOpenAll: false }}
          collapsed={layoutCollapsed}
          onCollapse={(nextCollapsed) => {
            if (mobileShell) setMobileCollapsed(nextCollapsed);
            else setDesktopCollapsed(nextCollapsed);
          }}
          menuItemRender={(item, dom) => item.path && !item.path.includes("#")
            ? <Link to={item.path}>{dom}</Link>
            : dom}
          footerRender={false}
          pageTitleRender={() => `${title} - ${branding.websiteName}`}
        >
          <div className="workspace-main">
            <PageContainer
              className="workspace-page"
              title={<Typography.Title level={2} className="page-title">{title}</Typography.Title>}
              breadcrumb={{ items: breadcrumbItems }}
            >
              {children}
              {denied ? (
                <Result
                  status="403"
                  title="403"
                  subTitle={t(moduleDenied ? "moduleUnavailable" : "forbidden")}
                />
              ) : runtimeLoading ? (
                <Skeleton active paragraph={{ rows: 8 }} />
              ) : section === "home" ? (
                <Workbench
                  displayName={user.data.displayName}
                  moduleCount={modules.data?.length ?? 0}
                  navigation={navigation}
                />
              ) : featurePage ? (
                <Suspense fallback={<Skeleton active paragraph={{ rows: 8 }} />}>
                  {featurePage}
                </Suspense>
              ) : runtimePage ? (
                <DynamicPage
                  title={title}
                  path={runtimePage.path ?? `/dashboard/${section}`}
                  componentKey={runtimePage.componentKey ?? runtimePage.menuCode ?? runtimePage.sectionKey}
                />
              ) : (
                <Result status="404" title="404" subTitle={t("notFound")} />
              )}
            </PageContainer>
          </div>
        </ProLayout>
      </div>
    </PlatformWatermark>
  );
}

function Workbench({
  displayName,
  moduleCount,
  navigation,
}: {
  displayName: string;
  moduleCount: number;
  navigation: ReturnType<typeof mergeRuntimeNavigation>;
}) {
  const { t } = usePreferences();
  const [greetingKey, setGreetingKey] = useState<WorkbenchGreetingKey>(() =>
    workbenchGreetingForHour(new Date().getHours()),
  );
  const pages = navigation.flatMap((group) => group.pages);

  useEffect(() => {
    const refreshGreeting = () =>
      setGreetingKey(workbenchGreetingForHour(new Date().getHours()));
    const interval = window.setInterval(refreshGreeting, 60_000);
    return () => window.clearInterval(interval);
  }, []);

  return (
    <div className="workbench">
      <ProCard>
        <Typography.Title level={4} className="workbench-title">
          {t(greetingKey, { name: displayName })}
        </Typography.Title>
      </ProCard>
      <div className="workbench-stats">
        <StatisticCard statistic={{ title: t("modules"), value: moduleCount }} />
        <StatisticCard statistic={{ title: t("navigation"), value: pages.length }} />
        <StatisticCard statistic={{ title: t("account"), value: displayName }} />
      </div>
      <div className="workbench-navigation-groups">
        {navigation.map((group) => (
          <ProCard
            key={group.id}
            title={localizedNavigationLabel(group.label, group.labelDefault, t)}
            extra={<Tag>{group.pages.length}</Tag>}
          >
            <Space wrap size={[8, 12]}>
              {group.pages.map((page) => (
                <Link key={page.id} to={page.path || `/dashboard/${page.id}`}>
                  <Button>{localizedNavigationLabel(page.label, page.labelDefault, t)}</Button>
                </Link>
              ))}
            </Space>
          </ProCard>
        ))}
      </div>
    </div>
  );
}

type WorkbenchGreetingKey =
  | "greetingDawn"
  | "greetingMorning"
  | "greetingNoon"
  | "greetingAfternoon"
  | "greetingEvening"
  | "greetingNight";

function workbenchGreetingForHour(hour: number): WorkbenchGreetingKey {
  if (hour >= 5 && hour < 8) return "greetingDawn";
  if (hour >= 8 && hour < 11) return "greetingMorning";
  if (hour >= 11 && hour < 14) return "greetingNoon";
  if (hour >= 14 && hour < 18) return "greetingAfternoon";
  if (hour >= 18 && hour < 22) return "greetingEvening";
  return "greetingNight";
}

function findRuntimePage(nodes: readonly RuntimeNavigationNode[], section: string): RuntimeNavigationNode | null {
  if (!isDashboardSection(section)) return null;
  const path = `/dashboard/${section}`;
  for (const node of nodes) {
    if (node.menuType === "PAGE" && (node.sectionKey === section || node.path === path)) return node;
    const nested = findRuntimePage(node.children ?? [], section);
    if (nested) return nested;
  }
  return null;
}
