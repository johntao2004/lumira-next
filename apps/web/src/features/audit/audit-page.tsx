"use client";
import {accountSecurityActions} from "@/platform/auth/security-actions";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/platform/api/client";
import { ResourceTable } from "@/platform/components/resource-table";
import type { PageResult } from "@/platform/api/pagination";
import { usePreferences } from "@/platform/providers";
import { formatDateTime } from "@/platform/i18n/format";
import type { MessageKey } from "@/platform/i18n/messages";
import { useListRequest } from "@/platform/routing/use-list-request";
import { listRequestParams } from "@/platform/api/pagination";
import { EllipsisText } from "@/platform/components/ellipsis-text";
import { Button } from "antd";
import { useState } from "react";
import { TableActions } from "@/platform/components/table-actions";
import { EventTraceDrawer } from "@/features/events/event-trace-drawer";
import type { EventTraceTarget } from "@/features/events/event-trace-drawer";

type AuditEvent={id:string;actor:string;action:string;targetId:string;createdAt:string};
const events:Record<string,MessageKey>={...accountSecurityActions,"draft.saved":"save","draft.deleted":"deleteRecord","notification.broadcast":"eventTraceNotificationBroadcast","feedback.created":"eventTraceFeedbackCreate","feedback.handled":"eventTraceFeedbackHandle","plugin.imported":"importPlugin","plugin.installed":"installPlugin","plugin.uninstalled":"uninstallPlugin","plugin.dataDeleted":"pluginDataDeleted","plugin.installFailed":"pluginStartFailed","plugin.uninstallFailed":"unavailable","module.enabled":"enableModule","module.disabled":"disableModule","mail.PENDING":"mailLeaseExpired","mail.EXPIRED":"mailExpired","mail.FAILED":"mailFailed","mail.UNCERTAIN":"mailUncertain","mail.started":"mailStarted","mail.ACCEPTED":"mailAccepted","mail.AUTHENTICATION_FAILED":"mailAuthenticationFailed","mail.CONNECTION_FAILED":"mailConnectionFailed","mail.DELIVERY_UNCERTAIN":"mailUncertain","mail.configurationUnavailable":"mailConfigurationUnavailable","setting.smtpPasswordUpdated":"smtpReplace","setting.smtpPasswordCleared":"smtpClear","account.avatarUpdated":"avatarUpdated","account.avatarRemoved":"avatarRemoved","account.forcedPasswordChanged":"passwordChanged","file.orphanExpired":"fileOrphanExpired","file.uploadExpired":"fileUploadExpired","file.cleanup":"fileCleanup","file.upload":"uploadFile","file.deleteRequested":"fileDeleteRequested","file.delete":"deleteRecord","file.trash":"moveToTrash","file.restore":"restoreFile","file.purgeRequested":"purgeFile","file.retentionExpired":"fileRetentionAutomatic","dictionaryItem.create":"createDictionaryItem","dictionaryItem.update":"edit","dictionaryItem.delete":"deleteRecord","dictionary.create":"createDictionary","dictionary.update":"edit","dictionary.delete":"deleteRecord","profile.update":"profile","session.revoke":"logout","session.adminRevoke":"revokeOnlineSession","session.adminRevokeUser":"revokeOnlineSession","user.create":"createUser","user.update":"edit","user.passwordReset":"resetPassword","user.fileQuota":"fileQuotaUpdated","user.roles":"assignRoles","user.organizations":"assignOrganizations","role.create":"createRole","role.update":"edit","role.delete":"deleteRecord","organization.create":"createOrganization","organization.update":"edit","organization.delete":"deleteRecord"};
export function AuditPage({ canTraceEvents = false }: { canTraceEvents?: boolean }) {
  const {t,locale}=usePreferences();
  const [traceTarget,setTraceTarget]=useState<EventTraceTarget|null>(null);
  const [request,setRequest]=useListRequest();
  const query=useQuery({queryKey:["audit",request],staleTime:0,queryFn:({signal})=>api<PageResult<AuditEvent>>(`/platform/audit?${listRequestParams(request)}`,{signal})});
  const actionLabel=(event:AuditEvent)=>`${t(event.action.startsWith("draft.")?"account":event.action.startsWith("feedback.")?"feedback":event.action.startsWith("notification.")?"notificationCenter":(event.action.startsWith("module.")||event.action.startsWith("plugin."))?"modules":event.action.startsWith("mail.")?"mailDeliveries":event.action.startsWith("setting.smtp")?"smtpSettings":event.action.startsWith("file.")?"myFiles":event.action.startsWith("dictionaryItem.")?"dictionaryItems":event.action.startsWith("dictionary.")?"dictionaries":event.action.startsWith("session.")?"mySessions":event.action.startsWith("role.")?"roles":event.action.startsWith("organization.")?"organizations":event.action.startsWith("mfa.")?"mfaTitle":"users")} · ${events[event.action]?t(events[event.action]):event.action}`;
  return <><div className="audit-page"><ResourceTable<AuditEvent> result={query.data} loading={query.isFetching} error={query.error} request={request} onChange={setRequest} onRetry={()=>void query.refetch()} columns={[
    {title:t("eventTime"),dataIndex:"createdAt",width:210,valueType:"dateTimeRange",ellipsis:{showTitle:false},render:(_,event)=><EllipsisText text={formatDateTime(event.createdAt,locale,{dateStyle:"medium",timeStyle:"medium"})}/>},
    {title:t("actor"),dataIndex:"actor",width:160,search:true,ellipsis:{showTitle:false},render:(_,event)=><EllipsisText text={event.actor}/>},
    {title:t("actions"),dataIndex:"action",width:370,search:true,ellipsis:{showTitle:false},render:(_,event)=><EllipsisText text={actionLabel(event)}/>},
    {title:t("targetId"),dataIndex:"targetId",width:250,search:true,ellipsis:{showTitle:false},render:(_,event)=><EllipsisText text={event.targetId}/>},
    ...(canTraceEvents ? [{title:t("eventTraceTitle"),key:"trace",search:false,render:(_:unknown,event:AuditEvent)=><TableActions><Button type="link" size="small" data-testid={`event-trace-audit-${event.id}`} onClick={()=>setTraceTarget({kind:"operation",auditId:event.id})}>{t("eventTraceOpen")}</Button></TableActions>}] : []),
  ]}/></div>{traceTarget && <EventTraceDrawer target={traceTarget} onClose={()=>setTraceTarget(null)}/>}</>;
}
