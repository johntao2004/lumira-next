import type { ReactNode } from "react";
import "./account-form-surface.css";

export function AccountSettingsSection({ title, children, className }: { title?: string; children: ReactNode; className?: string }) {
  return <section className={["account-settings-section", className].filter(Boolean).join(" ")}>
    {title && <h2 className="account-settings-section-title">{title}</h2>}
    {children}
  </section>;
}

export function AccountSettingsRow({ label, value, action }: { label: ReactNode; value?: ReactNode; action?: ReactNode }) {
  return <div className={["account-settings-row", value === undefined ? "account-settings-row-action-only" : ""].filter(Boolean).join(" ")}>
    <div className="account-settings-row-label">{label}</div>
    {value !== undefined && <div className="account-settings-row-value">{value}</div>}
    {action && <div className="account-settings-row-action">{action}</div>}
  </div>;
}
