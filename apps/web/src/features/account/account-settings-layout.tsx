"use client";

import { Menu, Skeleton, Typography } from "antd";
import type { MenuProps } from "antd";
import { Link, useLocation } from "@umijs/max";
import { useEffect, useRef, useState } from "react";
import { usePreferences } from "@/platform/providers";
import type { AccountSettingsRouteId } from "./manifest";
import "./account-settings-layout.css";

type Props = {
  routeId: AccountSettingsRouteId;
  rolePreviewAvailable: boolean;
  rolePreviewActive: boolean;
  wechatAvailable: boolean;
  children: React.ReactNode;
};

export function AccountSettingsLoadingSkeleton({ routeId }: { routeId: AccountSettingsRouteId }) {
  const { t } = usePreferences();

  return <div className="account-settings-loading-skeleton" role="status" aria-label={t("loading")} aria-busy="true">
    {routeId === "account-sessions" ? <AccountSessionsLoadingSkeleton /> : <Skeleton active title={false} paragraph={{ rows: 3 }} />}
  </div>;
}

export function AccountSessionsLoadingSkeleton() {
  return <div className="account-sessions-loading" aria-hidden="true">
    <div className="account-sessions-loading-search">
      <Skeleton.Input active block size="small" />
      <div className="account-sessions-loading-search-actions">
        <Skeleton.Input active block size="small" />
        <Skeleton.Input active block size="small" />
      </div>
    </div>
    <div className="account-sessions-loading-toolbar">
      <Skeleton.Input active block size="small" />
    </div>
    <div className="account-sessions-loading-table">
      <div className="account-sessions-loading-row account-sessions-loading-header">
        {Array.from({ length: 6 }, (_, index) => <Skeleton.Input key={index} active block size="small" />)}
      </div>
      {Array.from({ length: 2 }, (_, row) => <div className="account-sessions-loading-row" key={row}>
        {Array.from({ length: 6 }, (_, column) => <Skeleton.Input key={column} active block size="small" />)}
      </div>)}
    </div>
  </div>;
}

export function AccountSettingsLayout({ routeId, rolePreviewAvailable, rolePreviewActive, wechatAvailable, children }: Props) {
  const { t } = usePreferences();
  const { pathname } = useLocation();
  const navRef = useRef<HTMLElement | null>(null);
  const scrollFrameRef = useRef<number | null>(null);
  const [compact, setCompact] = useState(() => window.matchMedia("(max-width: 1199px)").matches);

  useEffect(() => {
    const media = window.matchMedia("(max-width: 1199px)");
    const update = () => setCompact(media.matches);
    update();
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  useEffect(() => {
    document.getElementById("account-settings-title")?.focus({ preventScroll: true });
  }, [pathname]);

  useEffect(() => {
    if (!compact) return;
    const nav = navRef.current;
    if (!nav) return;

    const alignCurrentItem = () => {
      if (scrollFrameRef.current !== null) {
        window.cancelAnimationFrame(scrollFrameRef.current);
      }
      scrollFrameRef.current = window.requestAnimationFrame(() => {
        scrollFrameRef.current = null;
        const nav = navRef.current;
        const currentLink = nav?.querySelector<HTMLAnchorElement>('a[aria-current="page"]');
        const selectedItem = currentLink?.closest<HTMLElement>(".ant-menu-item");
        if (!nav || !selectedItem) return;

        const navRect = nav.getBoundingClientRect();
        const itemRect = selectedItem.getBoundingClientRect();
        if (navRect.width <= 0 || nav.clientWidth <= 0 || itemRect.width <= 0) return;
        const visibleLeft = navRect.left + nav.clientLeft;
        const visibleRight = visibleLeft + nav.clientWidth;
        if (itemRect.left < visibleLeft) {
          nav.scrollLeft += itemRect.left - visibleLeft;
        } else if (itemRect.right > visibleRight) {
          nav.scrollLeft += itemRect.right - visibleRight;
        }
      });
    };

    const menu = nav.querySelector<HTMLElement>(".account-settings-menu");
    const observer = new ResizeObserver(alignCurrentItem);
    observer.observe(nav);
    if (menu) observer.observe(menu);
    alignCurrentItem();

    return () => {
      observer.disconnect();
      if (scrollFrameRef.current !== null) {
        window.cancelAnimationFrame(scrollFrameRef.current);
        scrollFrameRef.current = null;
      }
    };
  }, [compact, pathname, routeId]);

  const link = (id: AccountSettingsRouteId, label: string, disabled = false) => disabled
    ? <span aria-disabled="true">{label}</span>
    : <Link to={`/dashboard/${id}`} aria-current={routeId === id ? "page" : undefined}>{label}</Link>;

  const items: MenuProps["items"] = [
    { key: "account-security", label: link("account-security", t("profile")) },
    { key: "account-authentication-security", disabled: rolePreviewActive, label: link("account-authentication-security", t("accountSecurity"), rolePreviewActive) },
    ...(rolePreviewAvailable ? [{ key: "account-role-preview", label: link("account-role-preview", t("rolePreview")) }] : []),
    ...(wechatAvailable ? [{ key: "account-wechat", disabled: rolePreviewActive, label: link("account-wechat", t("wechatBinding"), rolePreviewActive) }] : []),
    { key: "account-password", label: link("account-password", t("changePassword")) },
    { key: "account-sessions", label: link("account-sessions", t("mySessions")) },
  ];

  const title = t({
    "account-profile": "profile",
    "account-security": "profile",
    "account-authentication-security": "accountSecurity",
    "account-role-preview": "rolePreview",
    "account-password": "changePassword",
    "account-sessions": "mySessions",
    "account-wechat": "wechatBinding",
  }[routeId]);

  return <section className="account-settings-panel" aria-labelledby="account-settings-title">
    <nav ref={navRef} className="account-settings-nav" aria-label={t("account")}>
      <Menu
        className="account-settings-menu"
        mode={compact ? "horizontal" : "inline"}
        style={compact ? { borderInlineEnd: 0, borderBlockEnd: 0 } : { borderInlineEnd: 0 }}
        selectedKeys={[routeId === "account-profile" ? "account-security" : routeId]}
        items={items}
      />
    </nav>
    <div className="account-settings-content">
      <Typography.Title id="account-settings-title" level={4} tabIndex={-1} className="account-settings-title">
        {title}
      </Typography.Title>
      <div className={`account-settings-body${routeId === "account-sessions" ? " account-settings-sessions-body" : ""}`}>{children}</div>
    </div>
  </section>;
}
