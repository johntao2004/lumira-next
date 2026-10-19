/**
 * 按精确 componentKey 注册和查找代码定义的组件，拒绝冲突覆盖，并把服务端标识作为数据而非可执行模块路径。
 * Register and resolve code-defined components by exact componentKey, rejecting conflicting replacements and treating server identifiers as data rather than executable module paths.
 */
"use client";

import type { ComponentType } from "react";

export type PageComponentProps = {
  title: string;
  path: string;
  componentKey: string;
};
export type PageComponent = ComponentType<PageComponentProps>;

const KEY_PATTERN = /^[a-z][a-z0-9._:-]{1,127}$/;
const components = new Map<string, PageComponent>();

/**
 * 组件标识只是数据，绝非模块名或可执行输入。
 * Component identifiers are data, never module names or executable input.
 */
export function isPageComponentKey(value: string | null | undefined): value is string {
  return typeof value === "string" && KEY_PATTERN.test(value);
}

/**
 * 按确切键注册代码定义的页面组件，模块可在客户端启动时调用；重复键将被拒绝，热更新重新注册同一函数除外。
 *
 * Register an exact, code-defined page component. Module packages can call
 * this during client bootstrap; duplicate keys are rejected unless the same
 * function is registered again by a hot-reload cycle.
 */
export function registerPageComponent(key: string, component: PageComponent): () => void {
  if (!isPageComponentKey(key) || typeof component !== "function") {
    throw new Error("Invalid page component registration");
  }
  const existing = components.get(key);
  if (existing && existing !== component) {
    throw new Error(`Page component already registered: ${key}`);
  }
  if (!existing) components.set(key, component);
  return () => {
    if (components.get(key) === component) components.delete(key);
  };
}

export function resolvePageComponent(key: string | null | undefined): PageComponent | null {
  return isPageComponentKey(key) ? components.get(key) ?? null : null;
}

export function registeredPageComponentKeys(): readonly string[] {
  return [...components.keys()].sort();
}
