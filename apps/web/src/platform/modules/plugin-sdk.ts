import * as module76 from "@/platform/auth/captcha-policy";
import * as module75 from "@/platform/profile-fields/localization";
import * as module74 from "@/platform/components/settings-secret-field";
import * as module73 from "@/platform/components/resource-picker";
import * as module72 from "@/platform/components/settings-enabled-controls";
import * as module71 from "@/platform/components/markdown-editor";
import * as module63 from "@/platform/components/drawer-actions";
import * as module58 from "@ant-design/x";
import * as module59 from "@/platform/conversations/conversation-markdown";
import * as module60 from "@/platform/conversations/assistant-avatar";
import * as module61 from "@/platform/files/file-download-action";
import * as module62 from "@/platform/components/account-avatar";
import * as module50 from "@/platform/auth/password-contract";
import * as module51 from "@/platform/auth/session-lifecycle";
import * as module52 from "@/platform/components/capacity-meter";
import * as module53 from "@/platform/files/download-blob";
import * as module54 from "@/platform/profile-fields/profile-field-renderer";
import * as module55 from "@/platform/profile-fields/types";
import * as module56 from "@/platform/templates/api";
import * as module57 from "@/platform/templates/field-labels";
import * as module47 from "@/platform/files/download-json";
import * as module48 from "@/platform/routing/list-location";
import * as module49 from "@/platform/routing/use-list-location";
import * as module45 from "@/platform/shell/routes";
import * as module46 from "@/platform/shell/page-component-registry";
import * as module43 from "@/platform/components/route-tabs";
import * as module44 from "@/platform/modules/route-tabs";
import * as module41 from "@/platform/components/use-query-filter-span";
import * as module42 from "@ant-design/pro-components";
import * as module37 from "@/platform/components/query-failure";
import * as module38 from "@/platform/components/resource-select";
import * as module39 from "@/platform/routing/use-tab-anchor";
import * as module34 from "@/platform/auth/security-actions";
import * as module35 from "@/platform/components/ellipsis-text";
import * as module36 from "@/platform/events/event-trace-drawer";
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
import * as module40 from "@/platform/routing/link";
import * as module64 from "@/platform/components/settings-section";
import * as module65 from "@/platform/modules/embedded-page";
import * as module66 from "@/platform/files/file-trash-actions";
import * as module67 from "@/platform/files/file-preview-drawer";
import * as module68 from "@/platform/files/file-name-cell";
import * as module69 from "@/platform/files/file-table-columns";
import * as module70 from "@/platform/files/use-file-batch-actions";
export const pluginSdkModules = {
 "@/platform/auth/captcha-policy": module76,
 "@/platform/profile-fields/localization": module75,
 "@/platform/components/settings-secret-field": module74,
 "@/platform/components/resource-picker": module73,
 "@/platform/components/settings-enabled-controls": module72,
 "@/platform/components/markdown-editor": module71,
 "@/platform/modules/embedded-page": module65,
 "@/platform/files/file-trash-actions": module66,
 "@/platform/files/file-preview-drawer": module67,
 "@/platform/files/file-name-cell": module68,
 "@/platform/files/file-table-columns": module69,
 "@/platform/files/use-file-batch-actions": module70,
 "@/platform/components/settings-section": module64,
 "@/platform/components/drawer-actions": module63,
 "@ant-design/x": module58,
 "@/platform/conversations/conversation-markdown": module59,
 "@/platform/conversations/assistant-avatar": module60,
 "@/platform/files/file-download-action": module61,
 "@/platform/components/account-avatar": module62,

 "@/platform/auth/password-contract": module50,
 "@/platform/auth/session-lifecycle": module51,
 "@/platform/components/capacity-meter": module52,
 "@/platform/files/download-blob": module53,
 "@/platform/profile-fields/profile-field-renderer": module54,
 "@/platform/profile-fields/types": module55,
 "@/platform/templates/api": module56,
 "@/platform/templates/field-labels": module57,

 "@/platform/files/download-json": module47,
 "@/platform/routing/list-location": module48,
 "@/platform/routing/use-list-location": module49,

 "@/platform/shell/routes": module45,
 "@/platform/shell/page-component-registry": module46,
 "@/platform/components/route-tabs": module43,
 "@/platform/modules/route-tabs": module44,
 "@/platform/components/use-query-filter-span": module41,
 "@ant-design/pro-components": module42,

 "@/platform/routing/link": module40,
 "@/platform/components/query-failure": module37,
 "@/platform/components/resource-select": module38,
 "@/platform/routing/use-tab-anchor": module39,

 "@/platform/auth/security-actions": module34,
 "@/platform/components/ellipsis-text": module35,
 "@/platform/events/event-trace-drawer": module36,

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
