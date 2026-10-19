/**
 * 将精确白名单路径映射到宿主共享模块，新增接口须同步白名单和契约测试，避免插件依赖私有实现。
 * Map exact allowlisted paths to shared host modules, updating the allowlist and contract tests with new APIs so plugins do not depend on private implementations.
 */
import * as module0 from "@/platform/api/client";
import * as module1 from "@/platform/api/errors";
import * as module2 from "@/platform/api/pagination";
import * as module3 from "@/platform/api/records";
import * as module4 from "@/platform/components/anchor-tabs";
import * as module5 from "@/platform/components/async-button";
import * as module6 from "@/platform/components/delete-action";
import * as module7 from "@/platform/components/feedback-bubble";
import * as module8 from "@/platform/components/form-drawer";
import * as module9 from "@/platform/components/platform-drawer";
import * as module10 from "@/platform/components/platform-form";
import * as module11 from "@/platform/components/resource-table";
import * as module12 from "@/platform/components/rich-text-editor";
import * as module13 from "@/platform/components/table-actions";
import * as module14 from "@/platform/components/use-centered-modal-confirm";
import * as module17 from "@/platform/forms/use-crud-drawer";
import * as module18 from "@/platform/forms/use-submit-action";
import * as module19 from "@/platform/i18n/format";
import * as module20 from "@/platform/i18n/messages";
import * as module21 from "@/platform/providers";
import * as module22 from "@/platform/routing/use-list-request";
import * as module23 from "@tanstack/react-query";
import * as module24 from "antd";
import * as module25 from "react";
import * as module26 from "react/jsx-dev-runtime";
import * as module27 from "react/jsx-runtime";
import * as module28 from "@/platform/auth/reauthentication-contract";
import * as module29 from "@/platform/auth/use-reauthentication-ticket";
import * as module30 from "@/platform/auth/passkey";
import * as module31 from "@/platform/components/editor-surface";
import * as module32 from "@/platform/auth/use-session-scope";
import * as module33 from "@/platform/auth/reauthentication-fields";
import * as module34 from "@/platform/i18n/builtin-dictionary-labels";
export const pluginSdkModules = {
 "@/platform/api/client": module0,
 "@/platform/api/errors": module1,
 "@/platform/api/pagination": module2,
 "@/platform/api/records": module3,
 "@/platform/components/anchor-tabs": module4,
 "@/platform/components/async-button": module5,
 "@/platform/components/delete-action": module6,
 "@/platform/components/feedback-bubble": module7,
 "@/platform/components/form-drawer": module8,
 "@/platform/components/platform-drawer": module9,
 "@/platform/components/platform-form": module10,
 "@/platform/components/resource-table": module11,
 "@/platform/components/rich-text-editor": module12,
 "@/platform/components/table-actions": module13,
 "@/platform/components/use-centered-modal-confirm": module14,
 "@/platform/forms/use-crud-drawer": module17,
 "@/platform/forms/use-submit-action": module18,
 "@/platform/i18n/format": module19,
 "@/platform/i18n/messages": module20,
 "@/platform/providers": module21,
 "@/platform/routing/use-list-request": module22,
 "@/platform/auth/reauthentication-contract": module28,
 "@/platform/auth/reauthentication-fields": module33,
 "@/platform/i18n/builtin-dictionary-labels": module34,
 "@/platform/auth/use-reauthentication-ticket": module29,
 "@/platform/auth/use-session-scope": module32,
 "@/platform/auth/passkey": module30,
 "@/platform/components/editor-surface": module31,
 "@tanstack/react-query": module23,
 "antd": module24,
 "react": module25,
 "react/jsx-dev-runtime": module26,
 "react/jsx-runtime": module27,
} satisfies Record<string, unknown>;

export type PluginSdkModules = typeof pluginSdkModules;
