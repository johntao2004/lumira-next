"use client";

import { Dropdown } from "antd";
import {cloneElement,useRef,type ReactElement} from "react";
import { usePreferences } from "../providers";

export type AccountActions = {
  onProfile: () => void;
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
    logout: actions.onLogout,
  };
  return <Dropdown autoFocus open={open} onOpenChange={onOpenChange} trigger={["click"]} placement={placement}
    classNames={{ root: "account-menu-popup" }}
    menu={{ items: [
      { key: "profile", label: t("profile") },
      { key: "logout", label: t("accountLogout") },
    ], onClick: ({ key }) => { triggerRef.current?.focus({preventScroll:true}); handlers[key]?.(); } }}>
    {cloneElement(children as ReactElement<{ref:typeof triggerRef}>,{ref:triggerRef})}
  </Dropdown>;
}
