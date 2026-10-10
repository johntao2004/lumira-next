"use client";

import { ProForm, type ProFormProps } from "@ant-design/pro-components";
import { Form } from "antd";
import type { ReactNode } from "react";
import { useSessionScope } from "../auth/use-session-scope";
import { guardFormFinish } from "./session-form-finish";

/**
 * The single form entry point for platform pages.
 *
 * Ant Design's Form API (Item, List, useForm and useWatch) is kept on the
 * exported component so existing form code can share one wrapper without
 * losing the native API. The wrapper gives every form the same class and a
 * vertical default; spacing itself remains token-driven in Providers.
 */
type PlatformFormProps<T = Record<string, any>> = Omit<ProFormProps<T>, "children"> & { children?: ReactNode };

function PlatformFormRoot<T = Record<string, any>>(props: PlatformFormProps<T>) {
  const { className, layout, submitter, omitNil, id, name, onFinish, ...rest } = props;
  const operationSignal = useSessionScope();
  return (
    <ProForm
      {...rest}
      id={id}
      onFinish={onFinish ? guardFormFinish(operationSignal, onFinish) : undefined}
      // Ant Design prefixes field DOM ids with the form name. Reuse an
      // explicit form id when callers have not supplied an independent name,
      // so coexisting forms can keep their field ids unique by default.
      name={name ?? id}
      layout={layout ?? "vertical"}
      submitter={submitter ?? false}
      omitNil={omitNil ?? false}
      className={["platform-form", className].filter(Boolean).join(" ")}
    />
  );
}

export const PlatformForm = Object.assign(PlatformFormRoot, {
  Item: Form.Item,
  List: Form.List,
  ErrorList: Form.ErrorList,
  Provider: Form.Provider,
  useForm: Form.useForm,
  useFormInstance: Form.useFormInstance,
  useWatch: Form.useWatch,
});
