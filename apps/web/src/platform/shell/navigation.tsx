/**
 * 把已筛选导航转换为 ProLayout 路径、标签和图标，保留顺序与深链，不扩大可访问页面集合。
 * Convert filtered navigation into ProLayout paths, labels and icons while preserving order and deep links without expanding the accessible page set.
 */
import type { ProLayoutProps } from "@ant-design/pro-components";
import {
  ApartmentOutlined,
  AppstoreOutlined,
  AuditOutlined,
  BgColorsOutlined,
  BellOutlined,
  BookOutlined,
  CommentOutlined,
  DashboardOutlined,
  FileOutlined,
  FileProtectOutlined,
  FolderOutlined,
  GlobalOutlined,
  HighlightOutlined,
  HomeOutlined,
  IdcardOutlined,
  KeyOutlined,
  MailOutlined,
  MenuOutlined,
  MessageOutlined,
  PayCircleOutlined,
  SafetyCertificateOutlined,
  SafetyOutlined,
  RobotOutlined,
  SendOutlined,
  SettingOutlined,
  TeamOutlined,
  ToolOutlined,
  UserOutlined,
} from "@ant-design/icons";
import type { ReactNode } from "react";
import type { MessageKey } from "../i18n/messages";
import type { NavigationGroup, NavigationPage } from "./routes";
import { localizedNavigationLabel } from "./routes";

type Translate = (key: MessageKey) => string;
type LayoutRoute = NonNullable<ProLayoutProps["route"]>;

export const SIDER_WIDTH = 256;
export const COLLAPSED_SIDER_WIDTH = 64;

function pagePath(page: NavigationPage): string {
  return page.path || `/dashboard/${page.id}`;
}

function groupIcon(groupId: string): ReactNode {
  if (groupId === "overview") return <HomeOutlined />;
  if (groupId === "ai-management") return <RobotOutlined />;
  if (groupId === "user-management") return <TeamOutlined />;
  if (groupId === "content-management") return <FolderOutlined />;
  if (groupId === "communication") return <MessageOutlined />;
  if (groupId === "payments") return <PayCircleOutlined />;
  if (groupId === "operations") return <DashboardOutlined />;
  if (groupId === "administration") return <SettingOutlined />;
  return <AppstoreOutlined />;
}

function pageIcon(pageId: string): ReactNode {
  const icons: Record<string, ReactNode> = {
    home: <DashboardOutlined />,
    files: <FolderOutlined />,
    "files-all": <FolderOutlined />,
    modules: <AppstoreOutlined />,
    monitoring: <DashboardOutlined />,
    users: <UserOutlined />,
    "online-users": <TeamOutlined />,
    roles: <SafetyCertificateOutlined />,
    organizations: <ApartmentOutlined />,
    dictionaries: <BookOutlined />,
    notifications: <BellOutlined />,
    branding: <BgColorsOutlined />,
    smtp: <MailOutlined />,
    "mail-deliveries": <MailOutlined />,
    sms: <MessageOutlined />,
    "sms-deliveries": <MessageOutlined />,
    "profile-fields": <IdcardOutlined />,
    agreements: <FileProtectOutlined />,
    watermark: <HighlightOutlined />,
    maintenance: <ToolOutlined />,
    security: <SafetyOutlined />,
    passkey: <KeyOutlined />,
    "sensitive-words": <SafetyOutlined />,
    localization: <GlobalOutlined />,
    audit: <AuditOutlined />,
    "event-outbox": <SendOutlined />,
    feedback: <CommentOutlined />,
    "payment-core-orders": <PayCircleOutlined />,
    "wechat-pay-orders": <PayCircleOutlined />,
    "alipay-pay-orders": <PayCircleOutlined />,
    "wechat-pay-settings": <PayCircleOutlined />,
    "alipay-pay-settings": <PayCircleOutlined />,
    navigation: <MenuOutlined />,
  };
  return icons[pageId] ?? <FileOutlined />;
}

/**
 * 将权限过滤且由服务端排序的路由树转换为 ProLayout 数据。
 * Convert the permission-filtered, server-ordered route tree into ProLayout data.
 */
export function buildProLayoutRoute(groups: readonly NavigationGroup[], translate: Translate): LayoutRoute {
  const pageRoute = (page: NavigationPage): LayoutRoute => ({
    key: page.id,
    path: pagePath(page),
    name: localizedNavigationLabel(page.label, page.labelDefault, translate),
    icon: pageIcon(page.id),
    locale: false,
  });
  const routes: LayoutRoute[] = groups.flatMap((group) => {
    if (group.standalone) {
      const [page, ...children] = group.pages;
      if (!page) return [];
      return [{ ...pageRoute(page), ...(children.length ? { routes: children.map(pageRoute) } : {}) }];
    }
    return [{
      key: group.id,
      path: pagePath(group.pages[0]),
      name: localizedNavigationLabel(group.label, group.labelDefault, translate),
      icon: groupIcon(group.id),
      locale: false,
      routes: group.pages.map(pageRoute),
    }];
  });
  return { path: "/dashboard", routes };
}
