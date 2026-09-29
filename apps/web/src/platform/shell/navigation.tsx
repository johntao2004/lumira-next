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
    navigation: <MenuOutlined />,
  };
  return icons[pageId] ?? <FileOutlined />;
}

/** Convert the permission-filtered, server-ordered route tree into ProLayout data. */
export function buildProLayoutRoute(groups: readonly NavigationGroup[], translate: Translate): LayoutRoute {
  const routes: LayoutRoute[] = groups.map((group) => ({
    key: group.id,
    path: pagePath(group.pages[0]),
    name: localizedNavigationLabel(group.label, group.labelDefault, translate),
    icon: groupIcon(group.id),
    locale: false,
    routes: group.pages.map((page) => ({
      key: page.id,
      path: pagePath(page),
      name: localizedNavigationLabel(page.label, page.labelDefault, translate),
      icon: pageIcon(page.id),
      locale: false,
    })),
  }));
  return { path: "/dashboard", routes };
}
