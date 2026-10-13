"use client";

import { Dropdown } from "antd";
import {cloneElement,useRef,type ReactElement} from "react";
import { usePreferences } from "../providers";

export type AccountActions = {
  displayName: string;
  onProfile: () => void;
  onAuthenticationSecurity: () => void;
  onRolePreview?: () => void;
  onWechat?: () => void;
  onSessions: () => void;
  onChangePassword: () => void;
  onLogout: () => void;
};

/** One action list for the mobile header and desktop navigation rail. */
export function AccountMenu({ children, open, onOpenChange, placement = "bottomRight", ...actions }: AccountActions & {
  children: ReactElement;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  placement?: "bottomRight" | "topLeft";
  "aria-hidden"?: boolean;
}) {
  const { t } = usePreferences();
  const triggerRef=useRef<HTMLElement|null>(null);
  const handlers: Record<string, () => void> = {
    profile: actions.onProfile,
    "account-security": actions.onAuthenticationSecurity,
    "role-preview": actions.onRolePreview ?? (() => {}),
    wechat: actions.onWechat ?? (() => {}),
    sessions: actions.onSessions,
    password: actions.onChangePassword,
    logout: actions.onLogout,
  };
  return <Dropdown autoFocus open={open} onOpenChange={onOpenChange} trigger={["click"]} placement={placement}
    classNames={{ root: "account-menu-popup" }}
    menu={{ items: [
      { key: "identity", label: actions.displayName, disabled: true },
      { key: "profile", label: t("profile") },
      { key: "account-security", label: t("accountSecurity") },
      ...(actions.onRolePreview ? [{ key: "role-preview", label: t("rolePreview") }] : []),
      ...(actions.onWechat ? [{ key: "wechat", label: t("wechatBinding") }] : []),
      { key: "password", label: t("changePassword") },
      { key: "sessions", label: t("mySessions") },
      { key: "logout", label: t("logout") },
    ], onClick: ({ key }) => { triggerRef.current?.focus({preventScroll:true}); handlers[key]?.(); } }}>
    {cloneElement(children as ReactElement<{ref:typeof triggerRef}>,{ref:triggerRef})}
  </Dropdown>;
}
