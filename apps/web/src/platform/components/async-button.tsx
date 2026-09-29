"use client";
import { Button, type ButtonProps } from "antd";

/** Keep the action name stable while Ant Design animates its loading icon. */
export function AsyncButton({ label, pending, disabled, ...props }: Omit<ButtonProps, "children" | "loading" | "aria-label" | "aria-busy"> & {
  label: string;
  pending: boolean;
}) {
  return <Button {...props} aria-label={label} aria-busy={pending} loading={pending} disabled={pending ? true : disabled}>{label}</Button>;
}
