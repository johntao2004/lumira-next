"use client";

import { ProForm, type ProFormProps } from "@ant-design/pro-components";
import { Form } from "antd";
import type { ReactNode } from "react";

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
  const { className, layout, submitter, omitNil, ...rest } = props;
  return (
    <ProForm
      {...rest}
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
