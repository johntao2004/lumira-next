"use client";

import { ProCard, StatisticCard } from "@ant-design/pro-components";
import { QuestionCircleOutlined } from "@ant-design/icons";
import { Col, Empty, List, Progress, Row, Select, Skeleton, Space, Statistic, Tag, Tooltip, Typography } from "antd";
import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useEffect, useRef, useState } from "react";
import { api } from "@/platform/api/client";
import { AsyncButton } from "@/platform/components/async-button";
import { QueryFailure } from "@/platform/components/query-failure";
import { formatDateTime } from "@/platform/i18n/format";
import type { MessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import { getMonitoringRefetchInterval, REALTIME_POLL_MS } from "./monitoring-refresh-interval";

type PlatformSummary = {
  totalUsers: number;
  activeUsers: number;
  activeSessions: number;
  enabledModules: number;
  totalModules: number;
  pendingOutbox: number;
  processingOutbox: number;
  failedOutbox: number;
  deadLetterOutbox: number;
  pendingMail: number;
  failedMail: number;
  pendingSms: number;
  failedSms: number;
  unreadNotifications: number;
  auditEvents24h: number;
};

type MachineRuntime = {
  operatingSystem: string;
  javaVersion: string;
  uptimeMillis: number;
  availableProcessors: number;
  systemCpuUsage: number | null;
  processCpuUsage: number | null;
  systemMemoryUsedBytes: number | null;
  systemMemoryTotalBytes: number | null;
  heapUsedBytes: number;
  heapMaxBytes: number | null;
  liveThreads: number;
  daemonThreads: number;
};

type DatabaseRuntime = {
  status: string;
  activeConnections: number | null;
  idleConnections: number | null;
  waitingConnections: number | null;
  maxConnections: number | null;
};

type FlowEvent = {
  sourceType: string;
  eventType: string;
  status: "PENDING" | "PROCESSING" | "DELIVERED" | "FAILED" | "DEAD_LETTER";
  attempts: number;
  createdAt: string;
};

type MonitoringSnapshot = {
  summary: PlatformSummary;
  machine: MachineRuntime;
  database: DatabaseRuntime;
  flow: { createdLast5m: number; deliveredLast5m: number; recentEvents: FlowEvent[] };
};

const eventStatusKeys: Record<FlowEvent["status"], MessageKey> = {
  PENDING: "eventPending",
  PROCESSING: "eventProcessing",
  DELIVERED: "eventDelivered",
  FAILED: "eventFailed",
  DEAD_LETTER: "eventDeadLetter",
};

type RefreshMode = "realtime" | "manual" | 5_000 | 10_000 | 30_000 | 60_000 | 300_000 | 900_000 | 3_600_000;

function percent(value: number | null): number | null {
  return value === null || !Number.isFinite(value) ? null : Math.max(0, Math.min(100, Math.round(value * 100)));
}

function formatBytes(bytes: number | null, locale: string): string {
  if (bytes === null || !Number.isFinite(bytes) || bytes < 0) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(value)} ${units[unit]}`;
}

function formatUptime(milliseconds: number): string {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000));
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h ${minutes}m`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m`;
  return `${seconds}s`;
}

function MetricLine({ label, value, percentage, warningThreshold = 90 }: { label: ReactNode; value: string; percentage: number | null; warningThreshold?: number | null }) {
  const strokeColor = percentage === null
    ? undefined
    : warningThreshold === null ? "var(--ant-color-primary)"
      : percentage >= warningThreshold ? "#ff4d4f" : percentage >= warningThreshold - 15 ? "#faad14" : undefined;
  return <div className="monitoring-metric-line">
    <Space className="monitoring-metric-heading" size={8}>
      <Typography.Text>{label}</Typography.Text>
      <Typography.Text type="secondary">{value}</Typography.Text>
    </Space>
    <Progress percent={percentage ?? 0} showInfo={false} size="small" strokeColor={strokeColor} />
  </div>;
}

function eventStatusColor(status: FlowEvent["status"]): string {
  if (status === "DELIVERED") return "success";
  if (status === "FAILED" || status === "DEAD_LETTER") return "error";
  if (status === "PROCESSING") return "processing";
  return "warning";
}

/** Read-only operational snapshot with a selectable interval while the page is visible. */
export function MonitoringPage() {
  const { t, locale } = usePreferences();
  const [refreshMode, setRefreshMode] = useState<RefreshMode>(5_000);
  // React Query's fetchFailureCount resets when each refetch begins. Keep the
  // streak across requests so repeated outage polls actually back off.
  const consecutiveFailures = useRef(0);
  const [pageActive, setPageActive] = useState(
    () => typeof document === "undefined" || document.visibilityState === "visible",
  );
  useEffect(() => {
    const updatePageActivity = () => {
      setPageActive(document.visibilityState === "visible");
    };
    document.addEventListener("visibilitychange", updatePageActivity);
    updatePageActivity();
    return () => {
      document.removeEventListener("visibilitychange", updatePageActivity);
    };
  }, []);
  const query = useQuery({
    queryKey: ["monitoring-summary"],
    queryFn: async ({ signal }) => {
      try {
        const snapshot = await api<MonitoringSnapshot>("/platform/monitoring/summary", { signal });
        consecutiveFailures.current = 0;
        return snapshot;
      } catch (error) {
        if (!signal.aborted) consecutiveFailures.current++;
        throw error;
      }
    },
    enabled: pageActive,
    retry: false,
    refetchInterval: (currentQuery) => pageActive
      && typeof document !== "undefined"
      && document.visibilityState === "visible"
      ? getMonitoringRefetchInterval(
          refreshMode,
          currentQuery.state.fetchStatus,
          consecutiveFailures.current,
        )
      : false,
    refetchIntervalInBackground: false,
  });
  const refreshOptions = [
    { value: "realtime", label: t("monitoringRefreshRealtime" as MessageKey, { count: REALTIME_POLL_MS / 1_000 }) },
    { value: 5_000, label: t("monitoringRefreshSeconds" as MessageKey, { count: 5 }) },
    { value: 10_000, label: t("monitoringRefreshSeconds" as MessageKey, { count: 10 }) },
    { value: 30_000, label: t("monitoringRefreshSeconds" as MessageKey, { count: 30 }) },
    { value: 60_000, label: t("monitoringRefreshMinutes" as MessageKey, { count: 1 }) },
    { value: 300_000, label: t("monitoringRefreshMinutes" as MessageKey, { count: 5 }) },
    { value: 900_000, label: t("monitoringRefreshMinutes" as MessageKey, { count: 15 }) },
    { value: 3_600_000, label: t("monitoringRefreshHours" as MessageKey, { count: 1 }) },
    { value: "manual", label: t("monitoringRefreshManual" as MessageKey) },
  ];
  const data = query.data;
  const summary = data?.summary;
  const machine = data?.machine;
  const systemCpu = percent(machine?.systemCpuUsage ?? null);
  const processCpu = percent(machine?.processCpuUsage ?? null);
  const systemMemoryPercent = machine?.systemMemoryUsedBytes !== null && machine?.systemMemoryTotalBytes
    ? percent(machine.systemMemoryUsedBytes / machine.systemMemoryTotalBytes)
    : null;
  const heapPercent = machine?.heapMaxBytes ? percent(machine.heapUsedBytes / machine.heapMaxBytes) : null;
  const machineNeedsAttention = (systemCpu ?? 0) >= 90 || (processCpu ?? 0) >= 90 || (heapPercent ?? 0) >= 90;
  const queueNeedsAttention = !!summary && (summary.failedOutbox + summary.deadLetterOutbox + summary.failedMail + summary.failedSms > 0);
  const databaseIsUp = data?.database.status === "UP";
  const databaseNeedsAttention = (data?.database.waitingConnections ?? 0) > 0
    || (data?.database.maxConnections !== null && data?.database.maxConnections !== undefined
      && data.database.activeConnections !== null && data.database.activeConnections >= data.database.maxConnections);
  const formatTime = (value: string) => formatDateTime(value, locale, { dateStyle: "short", timeStyle: "medium" });

  return <div className="monitoring-page">
    {query.error && <QueryFailure title={t("unavailable")} pending={query.isFetching} onRetry={() => void query.refetch()} />}
    <div className="monitoring-toolbar">
      <Space wrap size={[12, 8]}>
        {query.dataUpdatedAt > 0 && <Typography.Text type="secondary">
          {t("monitoringLastUpdated" as MessageKey, { time: formatDateTime(query.dataUpdatedAt, locale, { dateStyle: "short", timeStyle: "medium" }) })}
        </Typography.Text>}
        <Space size={8}>
          <Typography.Text>{t("monitoringRefreshFrequency" as MessageKey)}</Typography.Text>
          <Select<RefreshMode>
            aria-label={t("monitoringRefreshFrequency" as MessageKey)}
            value={refreshMode}
            options={refreshOptions}
            onChange={setRefreshMode}
            style={{ minWidth: 180 }}
          />
        </Space>
        <AsyncButton pending={query.isFetching} onClick={() => void query.refetch()} label={t("refreshPage")} />
      </Space>
    </div>

    {!data && query.isPending && <ProCard><Skeleton active paragraph={{ rows: 8 }} /></ProCard>}

    {data && machine && summary && <>
      <ProCard title={t("machineRuntime")} extra={<Tag color={machineNeedsAttention ? "warning" : "success"}>{machineNeedsAttention ? t("attentionRequired") : t("healthy")}</Tag>}>
        <Typography.Text className="monitoring-runtime-platform" type="secondary">{machine.operatingSystem} · Java {machine.javaVersion}</Typography.Text>
        <Row gutter={[24, 20]}>
          <Col xs={24} xl={16}>
            <Row gutter={[20, 16]}>
              <Col xs={24} sm={12}><MetricLine label={t("systemCpu")} percentage={systemCpu} value={systemCpu === null ? "—" : `${systemCpu}%`} /></Col>
              <Col xs={24} sm={12}><MetricLine label={t("processCpu")} percentage={processCpu} value={processCpu === null ? "—" : `${processCpu}%`} /></Col>
              <Col xs={24} sm={12}><MetricLine label={<Tooltip title={t("systemMemoryHint")}><span>{t("systemMemory")} <QuestionCircleOutlined /></span></Tooltip>} percentage={systemMemoryPercent} warningThreshold={null} value={`${formatBytes(machine.systemMemoryUsedBytes, locale)} / ${formatBytes(machine.systemMemoryTotalBytes, locale)}`} /></Col>
              <Col xs={24} sm={12}><MetricLine label={t("jvmHeap")} percentage={heapPercent} value={`${formatBytes(machine.heapUsedBytes, locale)} / ${formatBytes(machine.heapMaxBytes, locale)}`} /></Col>
            </Row>
          </Col>
          <Col xs={24} xl={8}>
            <Row gutter={[16, 16]}>
              <Col xs={12} xl={12}><Statistic title={t("uptime")} value={formatUptime(machine.uptimeMillis)} /></Col>
              <Col xs={12} xl={12}><Statistic title={t("processorCount")} value={machine.availableProcessors} /></Col>
              <Col xs={12} xl={12}><Statistic title={t("liveThreads")} value={machine.liveThreads} /></Col>
              <Col xs={12} xl={12}><Statistic title={t("daemonThreads")} value={machine.daemonThreads} /></Col>
            </Row>
          </Col>
        </Row>
      </ProCard>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <ProCard title={t("databasePool")} extra={<Tag color={!databaseIsUp ? "error" : databaseNeedsAttention ? "warning" : "success"}>
            {!databaseIsUp ? t("unavailable") : databaseNeedsAttention ? t("attentionRequired") : t("healthy")}
          </Tag>}>
            <Row gutter={[16, 16]}>
              <Col xs={12}><Statistic title={t("activeConnections")} value={data.database.activeConnections ?? "—"} /></Col>
              <Col xs={12}><Statistic title={t("idleConnections")} value={data.database.idleConnections ?? "—"} /></Col>
              <Col xs={12}><Statistic title={t("waitingConnections")} value={data.database.waitingConnections ?? "—"} /></Col>
              <Col xs={12}><Statistic title={t("maxConnections")} value={data.database.maxConnections ?? "—"} /></Col>
            </Row>
          </ProCard>
        </Col>
        <Col xs={24} lg={12}>
          <ProCard title={t("queueHealth")} extra={<Tag color={queueNeedsAttention ? "warning" : "success"}>{queueNeedsAttention ? t("attentionRequired") : t("healthy")}</Tag>}>
            <Row gutter={[16, 16]}>
              <Col xs={12} sm={6}><Statistic title={t("pendingOutbox")} value={summary.pendingOutbox} /></Col>
              <Col xs={12} sm={6}><Statistic title={t("processingOutbox")} value={summary.processingOutbox} /></Col>
              <Col xs={12} sm={6}><Statistic title={t("failedOutbox")} value={summary.failedOutbox} styles={{ content: { color: summary.failedOutbox ? "#d4380d" : undefined } }} /></Col>
              <Col xs={12} sm={6}><Statistic title={t("deadLetterOutbox")} value={summary.deadLetterOutbox} styles={{ content: { color: summary.deadLetterOutbox ? "#d4380d" : undefined } }} /></Col>
            </Row>
          </ProCard>
        </Col>
      </Row>

      <ProCard title={t("dataFlow")} extra={<Tag color="processing">{t("lastFiveMinutes")}</Tag>}>
        <div className="monitoring-flow-track">
          <div className="monitoring-flow-node">
            <Typography.Text type="secondary">{t("eventsCreatedLast5m")}</Typography.Text>
            <Typography.Title level={3}>{data.flow.createdLast5m}</Typography.Title>
          </div>
          <div className="monitoring-flow-node">
            <Typography.Text type="secondary">{t("eventsInQueue")}</Typography.Text>
            <Typography.Title level={3}>{summary.pendingOutbox + summary.processingOutbox}</Typography.Title>
          </div>
          <div className="monitoring-flow-node">
            <Typography.Text type="secondary">{t("eventsDeliveredLast5m")}</Typography.Text>
            <Typography.Title level={3}>{data.flow.deliveredLast5m}</Typography.Title>
          </div>
        </div>
      </ProCard>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <ProCard title={t("deliveryHealth")}>
            <Row gutter={[16, 16]}>
              <Col xs={12}><Statistic title={t("pendingMail")} value={summary.pendingMail} /></Col>
              <Col xs={12}><Statistic title={t("failedMail")} value={summary.failedMail} styles={{ content: { color: summary.failedMail ? "#d4380d" : undefined } }} /></Col>
              <Col xs={12}><Statistic title={t("pendingSms")} value={summary.pendingSms} /></Col>
              <Col xs={12}><Statistic title={t("failedSms")} value={summary.failedSms} styles={{ content: { color: summary.failedSms ? "#d4380d" : undefined } }} /></Col>
            </Row>
          </ProCard>
        </Col>
        <Col xs={24} lg={12}>
          <ProCard title={t("monitoringSummary")}>
            <Row gutter={[16, 16]}>
              <Col xs={12}><Statistic title={t("totalUsers")} value={summary.totalUsers} suffix={<Typography.Text type="secondary">/ {summary.activeUsers} {t("activeUsers")}</Typography.Text>} /></Col>
              <Col xs={12}><Statistic title={t("activeSessions")} value={summary.activeSessions} /></Col>
              <Col xs={12}><Statistic title={t("enabledModules")} value={summary.enabledModules} suffix={`/ ${summary.totalModules}`} /></Col>
              <Col xs={12}><Statistic title={t("auditEvents24h")} value={summary.auditEvents24h} /></Col>
            </Row>
            <div className="monitoring-secondary-metric"><Statistic title={t("unreadNotifications")} value={summary.unreadNotifications} /></div>
          </ProCard>
        </Col>
      </Row>

      <ProCard title={t("recentEvents")}>
        {data.flow.recentEvents.length === 0
          ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("noRecentEvents")} />
          : <List
              size="small"
              dataSource={data.flow.recentEvents}
              renderItem={event => <List.Item>
                <div className="monitoring-event-row">
                  <Space wrap size={[8, 4]}>
                    <Typography.Text strong>{event.eventType}</Typography.Text>
                    <Tag>{event.sourceType}</Tag>
                    <Tag color={eventStatusColor(event.status)}>{t(eventStatusKeys[event.status])}</Tag>
                    <Typography.Text type="secondary">{t("eventAttempts")}: {event.attempts}</Typography.Text>
                  </Space>
                  <Typography.Text type="secondary">{formatTime(event.createdAt)}</Typography.Text>
                </div>
              </List.Item>}
            />}
      </ProCard>
    </>}
  </div>;
}
