"use client";

import { AccountSettingsLayout, AccountSettingsLoadingSkeleton } from "@/features/account/account-settings-layout";
import { isAccountSettingsRoute } from "@/features/account/manifest";
import { ApiError, api, getAuthorizationContext, logout, mutate, sessionMe, shouldRefreshAuthorizationContextRevision } from "@/platform/api/client";
import type { AuthorizationContext, CurrentUser } from "@/platform/auth/api-types";
import { refreshAuthorizationScopedQueries } from "@/platform/auth/authorization-cache";
import { effectiveAuthorizationSignature, runtimeNavigationQueryKey } from "@/platform/auth/authorization-signature";
import { PlatformWatermark } from "@/platform/branding/watermark";
import { useBranding } from "@/platform/branding/provider";
import { AccountAvatar } from "@/platform/components/account-avatar";
import { QueryFailure } from "@/platform/components/query-failure";
import { EmbeddedFrontendPage } from "@/platform/modules/embedded-page";
import type { FrontendPageContext } from "@/platform/modules/types";
import { LanguageSwitcher } from "@/platform/i18n/language-switcher";
import { usePluginRuntime } from "@/platform/modules/plugin-runtime";
import { renderFrontendPage } from "@/platform/modules/registry";
import type { RuntimeNavigationNode } from "@/platform/navigation/runtime-types";
import { usePreferences } from "@/platform/providers";
import {
  PageContainer,
  ProCard,
  ProLayout,
} from "@ant-design/pro-components";
import { AppstoreFilled, MoonOutlined, SunOutlined } from "@ant-design/icons";
import { history, Link, useLocation } from "@umijs/max";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, App, Button, Result, Skeleton, Space, Statistic, Tag, Typography } from "antd";
import { Suspense, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { AccountMenu } from "./account-menu";
import { DynamicPage } from "./dynamic-page";
import { WorkspaceModuleActions } from "./workspace-module-actions";
import { useDisplayScale } from "../display/use-display-scale";
import { buildProLayoutRoute } from "./navigation";
import { formatDateTime } from "../i18n/format";
import { syncMenuState, type MenuState } from "./menu-state";
import { currentSessionRevision, currentSessionUserId, subscribeSessionBoundary } from "../auth/session-lifecycle";
const rolePreviewPageIds = new Set([
  "home",
  "users",
  "roles",
  "organizations",
  "files",
  "feedback",
  "account-role-preview",
  "account-profile",
  "account-security",
  "account-password",
  "account-sessions",
]);
import {
  availableNavigation,
  canAccessSection,
  canOperateSection,
  collapseTabbedNavigation,
  isSection,
  localizedNavigationLabel,
  mergeRuntimeNavigation,
  navigationPageIdForRoute,
  runtimeNavigationPageNode,
  runtimeBreadcrumbLabels,
  runtimeNavigationTrail,
  routes,
  type Section,
} from "./routes";

export function Workspace({ children = null }: { children?: React.ReactNode }) {
  usePluginRuntime();
  const sessionRevision = useSyncExternalStore(
    notify => subscribeSessionBoundary(() => notify()),
    currentSessionRevision,
    currentSessionRevision,
  );
  const { pathname } = useLocation();
  const displayScale = useDisplayScale();
  const section = pathname.split("/").filter(Boolean)[1] ?? "home";
  const branding = useBranding();
  const [desktopCollapsed, setDesktopCollapsed] = useState(false);
  const [mobileCollapsed, setMobileCollapsed] = useState(true);
  const [mobileShell, setMobileShell] = useState(() => window.matchMedia("(max-width: 767px)").matches);
  const [menuState, setMenuState] = useState<MenuState>({ activeGroup: null, openGroups: [] });
  const previousSessionRevision = useRef(sessionRevision);
  const previousAuthorizationSignature = useRef<string | null>(null);
  const client = useQueryClient();
  const { dark, toggleDark, t } = usePreferences();
  const { message } = App.useApp();
  const errorText = (error: unknown) =>
    t(error instanceof ApiError && error.status === 403 ? "forbidden" : "unavailable");

  // ProLayout renders its mobile navigation trigger as a bare <span onClick>. Give it a role, a name
  // and keyboard activation so it is reachable without a pointer; the layout itself is untouched.
  useEffect(() => {
    const selector = ".ant-pro-global-header-collapsed-button";
    const label = t("openMenu");
    const patch = () => document.querySelectorAll<HTMLElement>(selector).forEach((element) => {
      element.setAttribute("role", "button");
      element.setAttribute("aria-label", label);
      element.tabIndex = 0;
    });
    const onKeyDown = (event: KeyboardEvent) => {
      const trigger = (event.target as HTMLElement | null)?.closest?.<HTMLElement>(selector);
      if (trigger && (event.key === "Enter" || event.key === " ")) {
        event.preventDefault();
        trigger.click();
      }
    };
    patch();
    const observer = new MutationObserver(patch);
    observer.observe(document.body, { childList: true, subtree: true });
    document.addEventListener("keydown", onKeyDown);
    return () => {
      observer.disconnect();
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [t]);

  const user = useQuery({
    queryKey: ["session"],
    staleTime: 0,
    refetchOnWindowFocus: true,
    refetchInterval: 30_000,
    queryFn: ({ signal }) => sessionMe<CurrentUser>("workspace-session", signal),
  });
  const authorizationSignature = user.data ? effectiveAuthorizationSignature(user.data) : null;
  useEffect(() => {
    if (!authorizationSignature) {
      previousAuthorizationSignature.current = null;
      return;
    }
    const previous = previousAuthorizationSignature.current;
    previousAuthorizationSignature.current = authorizationSignature;
    if (previous === null || previous === authorizationSignature) return;
    void refreshAuthorizationScopedQueries(client, runtimeNavigationQueryKey(user.data, sessionRevision));
  }, [authorizationSignature, client, sessionRevision, user.data]);
  useEffect(() => {
    if (previousSessionRevision.current === sessionRevision) return;
    previousSessionRevision.current = sessionRevision;
    setMenuState({ activeGroup: null, openGroups: [] });
  }, [sessionRevision]);
  useEffect(() => {
    let refreshGeneration = 0;
    const reauthenticate = () => {
      const generation = ++refreshGeneration;
      const revision = currentSessionRevision();
      void client.cancelQueries({ queryKey: ["session"], exact: true }).then(() => {
        if (generation !== refreshGeneration || revision !== currentSessionRevision()) return;
        return client.fetchQuery({
          queryKey: ["session"],
          staleTime: 0,
          queryFn: ({ signal }) => sessionMe<CurrentUser>("workspace-session", signal),
        });
      }).catch(() => {});
    };
    window.addEventListener("lumira:session-revalidate", reauthenticate);
    return () => {
      refreshGeneration++;
      window.removeEventListener("lumira:session-revalidate", reauthenticate);
    };
  }, [client]);
  const runtimeNavigation = useQuery({
    queryKey: runtimeNavigationQueryKey(user.data, sessionRevision),
    queryFn: ({ signal }) => api<RuntimeNavigationNode[]>("/platform/navigation", { signal }),
    enabled: Boolean(user.data),
    staleTime: 0,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
  });
  const removedDraftsRoute = section === "account-drafts";
  const runtimePageNode = removedDraftsRoute ? undefined : runtimeNavigationPageNode(runtimeNavigation.data ?? [], section, pathname);
  const routeSection = runtimePageNode?.sectionKey?.trim() || section;
  const accountSettingsRouteId = isAccountSettingsRoute(routeSection) ? routeSection : null;
  const accountSettingsPage = accountSettingsRouteId !== null;
  const routeForTitle = isSection(routeSection) ? routes[routeSection] : undefined;
  const runtimeTrail = runtimeNavigationTrail(runtimeNavigation.data ?? [], routeSection, pathname);
  const runtimePageForTitle = runtimeTrail?.at(-1) ?? null;
  const previewActive = user.data?.authorizationContext?.preview?.state === "ACTIVE";
  const allNavigation = collapseTabbedNavigation(mergeRuntimeNavigation(
    availableNavigation(
      user.data?.authorities ?? [],
      user.data?.enabledModules ?? [],
      user.data?.globalAuthorities ?? [],
    ),
    runtimeNavigation.data,
  ));
  const navigation = previewActive
    ? allNavigation
        .map(group => ({ ...group, pages: group.pages.filter(page => rolePreviewPageIds.has(page.id)) }))
        .filter(group => group.pages.length > 0)
    : allNavigation;
  const navigationSection = navigationPageIdForRoute(routeSection, navigation);
  const activeGroup = navigation.find((group) =>
    group.pages.some((page) => page.id === navigationSection || page.path === pathname),
  );
  const menuGroupId = (group: (typeof navigation)[number] | undefined) =>
    group?.standalone ? group.pages[0]?.id ?? null : group?.id ?? null;
  const activeGroupId = menuGroupId(activeGroup);
  const availableGroupIds = navigation.map(group => menuGroupId(group)).filter((id): id is string => Boolean(id));
  // Hash/query changes and Tab routes within the same group leave manual expansion untouched.
  useEffect(() => {
    setMenuState(current => syncMenuState(current, availableGroupIds, activeGroupId));
  }, [availableGroupIds.join("\0"), activeGroupId]);
  const resolvedPageTitle = runtimePageForTitle
    ? localizedNavigationLabel(runtimePageForTitle.labelKey, runtimePageForTitle.labelDefault, t)
    : routeForTitle
      ? t(routeForTitle.label)
      : t("notFound");

  useEffect(() => {
    document.title = `${resolvedPageTitle} - ${branding.websiteName}`;
  }, [branding.websiteName, resolvedPageTitle]);

  useEffect(() => {
    const refresh = () => {
      void client.invalidateQueries();
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
    let refreshInFlight: Promise<void> | null = null;
    const refresh = () => {
      if (refreshInFlight) return refreshInFlight;
      refreshInFlight = (async () => {
        await client.invalidateQueries({ queryKey: ["session"] });
      })().finally(() => { refreshInFlight = null; });
      return refreshInFlight;
    };
    const channel = typeof BroadcastChannel === "undefined" ? null : new BroadcastChannel("lumira:authorization-context");
    let lastSeenRevision = getAuthorizationContext()?.revision ?? null;
    if (channel) channel.onmessage = event => {
        const value = event.data as { revision?: unknown } | null;
        if (!value || (value.revision !== null && typeof value.revision !== "string")) return;
        const revision = value.revision as string | null;
        const currentRevision = getAuthorizationContext()?.revision ?? null;
        if (!shouldRefreshAuthorizationContextRevision(revision, lastSeenRevision, currentRevision)) {
          lastSeenRevision = revision;
          return;
        }
        lastSeenRevision = revision;
        refresh();
      };
    window.addEventListener("lumira:authorization-context-refresh", refresh);
    return () => {
      channel?.close();
      window.removeEventListener("lumira:authorization-context-refresh", refresh);
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
  if (user.data && user.data.userId !== currentSessionUserId()) {
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
    onProfile: () => history.push("/dashboard/account-security"),
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
  const hasRouteAccess = (routeId: string) => isSection(routeId) && canAccessSection(
    routeId as Section,
    user.data.authorities,
    user.data.enabledModules ?? [],
    user.data.globalAuthorities ?? [],
  );
  const hasRouteOperation = (routeId: string, actionId: string) => canOperateSection(
    routeId,
    actionId,
    user.data.authorities,
    user.data.enabledModules ?? [],
    user.data.globalAuthorities ?? [],
  );
  const hasOperation = (actionId: string) => hasRouteOperation(routeSection, actionId);
  const activeRoute = routeForTitle;
  const runtimePage = runtimePageForTitle;
  const moduleDenied = Boolean(activeRoute?.module && !(user.data.enabledModules ?? []).includes(activeRoute.module));
  const previewUnavailable = previewActive && !rolePreviewPageIds.has(routeSection);
  const runtimeLoading = !activeRoute && runtimeNavigation.isPending;
  const denied = previewUnavailable || runtimePageNode?.routable === false || (runtimeLoading
    ? false
    : activeRoute
      ? !canAccessSection(routeSection as Section, user.data.authorities, user.data.enabledModules ?? [], user.data.globalAuthorities ?? [])
      : !runtimePage);
  const title = resolvedPageTitle;
  const pageContext: FrontendPageContext = {
        authorities: user.data.authorities,
        hasGlobal,
        hasRouteAccess,
        hasRouteOperation,
        hasOperation,
        readOnlyPreview: Boolean(user.data.authorizationContext?.preview),
      };
  const featurePage = !denied && routeSection !== "home"
    ? renderFrontendPage(routeSection, pageContext)
    : null;
  const layoutRoute = buildProLayoutRoute(navigation, t);
  const activeMenuPage = navigation.flatMap((group) => group.pages)
    .find((page) => page.id === navigationSection || page.path === pathname);
  const layoutPath = activeMenuPage?.path || (activeMenuPage ? `/dashboard/${activeMenuPage.id}` : pathname);
  const breadcrumbItems = runtimeBreadcrumbLabels(
    runtimeTrail,
    title,
    node => localizedNavigationLabel(node.labelKey, node.labelDefault, t),
  ).map((label) => ({ title: label }));
  const utilityActions = () => [
    <Button
      type="text"
      key="theme"
      icon={dark ? <SunOutlined /> : <MoonOutlined />}
      onClick={toggleDark}
      aria-label={t(dark ? "lightMode" : "darkMode")}
    />,
    <LanguageSwitcher key="language" />,
    <WorkspaceModuleActions key="module-actions" />,
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
    <PlatformWatermark key={`${user.data.userId}:${sessionRevision}`}>
      <div className="workspace-shell">
        <ProLayout
          className="workspace"
          token={{
            bgLayout: null,
            header: {
              colorBgHeader: "var(--ant-color-bg-container)",
              colorBgScrollHeader: "var(--ant-color-bg-container)",
            },
            pageContainer: {
              paddingInlinePageContainerContent: mobileShell ? 16 : 24,
              paddingBlockPageContainerContent: mobileShell ? 16 : 24,
            },
            ...(dark ? {
              sider: {
                colorBgMenuItemSelected: "var(--ant-color-primary)",
                colorTextMenuSelected: "var(--ant-color-text-light-solid)",
                colorTextSubMenuSelected: "var(--ant-color-text-light-solid)",
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
          disableMobile={!mobileShell}
          siderWidth={displayScale.siderWidth}
          menu={{ collapsedWidth: displayScale.collapsedSiderWidth, defaultOpenAll: false }}
          collapsed={layoutCollapsed}
          menuProps={{
            selectedKeys: activeMenuPage ? [activeMenuPage.id] : [],
            openKeys: menuState.openGroups,
            onOpenChange: (keys) => {
              // Ant Design emits an empty list when the whole sidebar folds. Keep it for reopening.
              if (!layoutCollapsed) setMenuState(current => ({ ...current, openGroups: keys.map(String).filter(key => availableGroupIds.includes(key)) }));
            },
          }}
          onCollapse={(nextCollapsed) => {
            if (mobileShell) setMobileCollapsed(nextCollapsed);
            else setDesktopCollapsed(nextCollapsed);
          }}
          menuItemRender={(item, dom) => item.path && !item.path.includes("#")
            ? <Link to={item.path} onClick={() => { if (mobileShell) setMobileCollapsed(true); }}>{dom}</Link>
            : dom}
          footerRender={false}
          pageTitleRender={() => `${title} - ${branding.websiteName}`}
        >
          <div className="workspace-main">
            {user.data.authorizationContext?.rolePreviewSupported && user.data.authorizationContext.preview && <RolePreviewBanner context={user.data.authorizationContext} actorName={user.data.displayName} />}
            <PageContainer
              className="workspace-page"
              title={accountSettingsPage ? false : <Typography.Title level={2} className="page-title">{title}</Typography.Title>}
              breadcrumbRender={accountSettingsPage ? false : undefined}
              breadcrumb={{ items: breadcrumbItems }}
            >
              {children}
              {removedDraftsRoute ? (
                <Result status="404" title="404" subTitle={t("notFound")} />
              ) : denied ? (
                <Result
                  status="403"
                  title="403"
                  subTitle={t(previewUnavailable ? "rolePreviewPageUnavailable" : moduleDenied ? "moduleUnavailable" : "forbidden")}
                />
              ) : accountSettingsRouteId ? (
                <AccountSettingsLayout
                  key={accountSettingsRouteId}
                  routeId={accountSettingsRouteId}
                  rolePreviewAvailable={user.data.authorizationContext?.rolePreviewSupported === true}
                  rolePreviewActive={previewActive}
                  wechatAvailable={user.data.effectiveAuthentication?.allowedMethods.includes("WECHAT") === true}
                >
                  {runtimeLoading ? (
                    <AccountSettingsLoadingSkeleton routeId={accountSettingsRouteId} />
                  ) : featurePage ? (
                    <Suspense fallback={<AccountSettingsLoadingSkeleton routeId={accountSettingsRouteId} />}>
                      {featurePage}
                    </Suspense>
                  ) : runtimePage ? (
                    <DynamicPage
                      title={title}
                      path={runtimePage.path ?? `/dashboard/${routeSection}`}
                      componentKey={runtimePage.componentKey ?? runtimePage.menuCode ?? runtimePage.sectionKey}
                    />
                  ) : (
                    <Result status="404" title="404" subTitle={t("notFound")} />
                  )}
                </AccountSettingsLayout>
              ) : runtimeLoading ? (
                <Skeleton active paragraph={{ rows: 8 }} />
              ) : routeSection === "home" ? (
                <Workbench
                  pageContext={pageContext}
                  displayName={user.data.displayName}
                  userId={user.data.userId}
                  globalAuthorities={user.data.globalAuthorities ?? []}
                  authorizationRevision={user.data.authorizationContext?.revision ?? null}
                  previewActive={previewActive}
                  navigation={navigation}
                />
              ) : featurePage ? (
                <Suspense fallback={<Skeleton active paragraph={{ rows: 8 }} />}>
                  {featurePage}
                </Suspense>
              ) : runtimePage ? (
                <DynamicPage
                  title={title}
                  path={runtimePage.path ?? `/dashboard/${routeSection}`}
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

function RolePreviewBanner({ context, actorName }: { context: AuthorizationContext; actorName: string }) {
  const { t, locale } = usePreferences();
  const client = useQueryClient();
  const { message } = App.useApp();
  const [pending, setPending] = useState(false);
  const preview = context.preview;
  if (!preview) return null;

  async function exit() {
    if (!context.revision || pending) return;
    setPending(true);
    try {
      await mutate<void>("/account/role-preview", "DELETE", { authorizationContextRevision: context.revision });
      await client.invalidateQueries();
    } catch (error) {
      void message.error(t(error instanceof ApiError && error.message === "authorizationContextChanged" ? "authorizationContextChanged" : error instanceof ApiError && error.status === 403 ? "rolePreviewBlocked" : "unavailable"));
    } finally {
      setPending(false);
    }
  }

  return <Alert
    className="mb-4"
    showIcon
    type={preview.state === "BLOCKED" ? "warning" : "info"}
    message={t(preview.state === "BLOCKED" ? "rolePreviewBlocked" : "rolePreviewActive", { role: preview.roleName })}
    description={<Space wrap size={[8, 4]}>
      <span>{t("rolePreviewActor", { name: actorName })}</span>
      <span>{t("rolePreviewExpires", { time: formatDateTime(preview.expiresAt, locale) })}</span>
      {preview.limitedByActor && <span>{t("rolePreviewLimitedByActor")}</span>}
    </Space>}
    action={<Space wrap>
      {preview.state === "BLOCKED" && <Button size="small" onClick={() => history.push("/dashboard/account-role-preview")}>{t("rolePreviewChoose")}</Button>}
      <Button size="small" danger loading={pending} disabled={!context.revision} onClick={() => void exit()}>{t("rolePreviewExit")}</Button>
    </Space>}
  />;
}

function Workbench({
  pageContext,
  displayName,
  userId,
  globalAuthorities,
  authorizationRevision,
  previewActive,
  navigation,
}: {
  pageContext: FrontendPageContext;
  displayName: string;
  userId: string;
  globalAuthorities: readonly string[];
  authorizationRevision: string | null;
  previewActive: boolean;
  navigation: ReturnType<typeof mergeRuntimeNavigation>;
}) {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [greetingKey, setGreetingKey] = useState<WorkbenchGreetingKey>(() =>
    workbenchGreetingForHour(new Date().getHours()),
  );
  const canReadSystemOverview = !previewActive
    && navigation.some(group => group.pages.some(page => page.id === "monitoring"));
  const monitoringQueryKey = ["workbench-monitoring-summary", userId, authorizationRevision] as const;
  const monitoring = useQuery({
    queryKey: monitoringQueryKey,
    queryFn: ({ signal }) => api<WorkbenchMonitoringSnapshot>("/platform/monitoring/summary", { signal }),
    enabled: canReadSystemOverview,
    staleTime: 30_000,
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
    retry: false,
  });

  useEffect(() => {
    if (canReadSystemOverview) return;
    const queryKey = ["workbench-monitoring-summary"] as const;
    void client.cancelQueries({ queryKey }).then(() => client.removeQueries({ queryKey }));
  }, [canReadSystemOverview, client]);

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
      {canReadSystemOverview ? (
        <WorkbenchSystemOverview
          data={monitoring.data}
          loading={monitoring.isPending}
          error={monitoring.error}
          pending={monitoring.isFetching}
          onRetry={() => void monitoring.refetch()}
        />
      ) : (
        <Suspense fallback={null}>
          <EmbeddedFrontendPage routeId="files" context={{ ...pageContext, embedded: { presentation: "summary" } }} />
        </Suspense>
      )}
    </div>
  );
}

type WorkbenchMonitoringSnapshot = {
  summary: {
    totalUsers: number;
    activeUsers: number;
    activeSessions: number;
    pendingOutbox: number;
    processingOutbox: number;
    failedOutbox: number;
    deadLetterOutbox: number;
    pendingMail: number;
    failedMail: number;
    pendingSms: number;
    failedSms: number;
    unreadNotifications: number;
    auditEvents24h: number;
  };
};

function WorkbenchSystemOverview({
  data,
  loading,
  error,
  pending,
  onRetry,
}: {
  data: WorkbenchMonitoringSnapshot | undefined;
  loading: boolean;
  error: Error | null;
  pending: boolean;
  onRetry: () => void;
}) {
  const { t } = usePreferences();
  const summary = data?.summary;
  const metricGroups = summary ? [
    {
      title: "monitoringSummary" as const,
      metrics: [
        {
          label: "totalUsers" as const,
          value: summary.totalUsers,
          suffix: <Typography.Text type="secondary">/ {summary.activeUsers} {t("activeUsers")}</Typography.Text>,
        },
        { label: "activeSessions" as const, value: summary.activeSessions },
        { label: "auditEvents24h" as const, value: summary.auditEvents24h },
        { label: "unreadNotifications" as const, value: summary.unreadNotifications },
      ],
    },
    {
      title: "queueHealth" as const,
      metrics: [
        { label: "pendingOutbox" as const, value: summary.pendingOutbox },
        { label: "processingOutbox" as const, value: summary.processingOutbox },
        { label: "failedOutbox" as const, value: summary.failedOutbox, attention: true },
        { label: "deadLetterOutbox" as const, value: summary.deadLetterOutbox, attention: true },
      ],
    },
    {
      title: "deliveryHealth" as const,
      metrics: [
        { label: "pendingMail" as const, value: summary.pendingMail },
        { label: "failedMail" as const, value: summary.failedMail, attention: true },
        { label: "pendingSms" as const, value: summary.pendingSms },
        { label: "failedSms" as const, value: summary.failedSms, attention: true },
      ],
    },
  ] : [];

  return (
    <section className="workbench-overview" aria-label={t("monitoringSummary")}>
      {error && <QueryFailure title={t("unavailable")} pending={pending} onRetry={onRetry} />}
      {loading ? (
        <ProCard className="workbench-overview-loading"><Skeleton active paragraph={{ rows: 2 }} /></ProCard>
      ) : summary ? (
        <div className="workbench-overview-groups">
          {metricGroups.map(group => (
            <ProCard key={group.title} title={t(group.title)} className="workbench-overview-card">
              <div className="workbench-overview-metrics">
                {group.metrics.map(metric => (
                  <div key={metric.label} className="workbench-overview-metric">
                    <Statistic
                      title={t(metric.label)}
                      value={metric.value}
                      suffix={"suffix" in metric ? metric.suffix : undefined}
                      styles={{ content: { color: "attention" in metric && metric.attention && metric.value > 0 ? "var(--ant-color-error)" : undefined } }}
                    />
                  </div>
                ))}
              </div>
            </ProCard>
          ))}
        </div>
      ) : null}
    </section>
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
