"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  theme,
  type TableColumnsType,
} from "antd";
import { ApiError, api } from "@/platform/api/client";
import { useSessionScope } from "@/platform/auth/use-session-scope";
import {
  appendSample,
  buildDependencyGraph,
  formatMetric,
  mergeModuleRecords,
  normalizeStatus,
  type DependencyEdge,
  type DependencyNode,
  type MergedModuleRecord,
  type ModuleRecord,
  type ObserverSample,
  type PluginRecord,
} from "./model.mjs";

const QUERY_ROOT = ["runtime-observer"] as const;

type MonitoringSummary = {
  activeUsers?: number | null;
  activeSessions?: number | null;
  enabledModules?: number | null;
  totalModules?: number | null;
  pendingOutbox?: number | null;
  processingOutbox?: number | null;
  failedOutbox?: number | null;
  deadLetterOutbox?: number | null;
  pendingMail?: number | null;
  failedMail?: number | null;
  pendingSms?: number | null;
  failedSms?: number | null;
  eventsCreatedLast5m?: number | null;
  eventsDeliveredLast5m?: number | null;
  retryPendingOutbox?: number | null;
  overdueOutbox?: number | null;
  measuredAt?: string | null;
};

type EventRecord = {
  id?: string | null;
  sourceType?: string | null;
  eventType?: string | null;
  status?: string | null;
  attempts?: number | null;
  createdAt?: string | null;
};

type MonitoringSnapshot = {
  summary?: MonitoringSummary | null;
  machine?: {
    operatingSystem?: string | null;
    javaVersion?: string | null;
    uptimeMillis?: number | null;
    availableProcessors?: number | null;
    systemCpuUsage?: number | null;
    processCpuUsage?: number | null;
    systemMemoryUsedBytes?: number | null;
    systemMemoryTotalBytes?: number | null;
    heapUsedBytes?: number | null;
    heapMaxBytes?: number | null;
    liveThreads?: number | null;
    daemonThreads?: number | null;
  } | null;
  database?: {
    status?: string | null;
    activeConnections?: number | null;
    idleConnections?: number | null;
    waitingConnections?: number | null;
    maxConnections?: number | null;
  } | null;
  flow?: {
    status?: string | null;
    producerTypes?: number | null;
    consumerTypes?: number | null;
    connectedTypes?: number | null;
    unmatchedConsumerTypes?: number | null;
    missingConsumers?: Array<{ sourceType?: string | null; eventType?: string | null }> | null;
    createdLast5m?: number | null;
    deliveredLast5m?: number | null;
    retryPending?: number | null;
    overdue?: number | null;
    recentEvents?: EventRecord[] | null;
  } | null;
};

type EventOutboxPage = { items?: EventRecord[] | null; total?: number | null };

type AuthMethods = {
  methods?: string[] | null;
  registration?: {
    supported?: boolean | null;
    available?: boolean | null;
    channels?: string[] | null;
    path?: string | null;
  } | null;
  capabilities?: {
    loginCode?: boolean | null;
    wechat?: boolean | null;
    captcha?: boolean | null;
    rolePreview?: boolean | null;
  } | null;
  captcha?: {
    required?: boolean | null;
    type?: string | null;
    actions?: string[] | null;
    alwaysActions?: string[] | null;
  } | null;
};

type RegistrationConfig = {
  available?: boolean | null;
  reason?: string | null;
  channels?: string[] | null;
  policyVersion?: number | null;
  codePolicy?: { expiresInSeconds?: number | null; cooldownSeconds?: number | null } | null;
};

type QueryView<T> = {
  data?: T;
  error?: unknown;
  isPending: boolean;
  isError: boolean;
  dataUpdatedAt: number;
};

type Props = {
  canRead: boolean;
  canReadMonitoring: boolean;
  canListModules: boolean;
  canReadEvents: boolean;
  readOnlyPreview: boolean;
};

type Selection =
  | { kind: "node"; node: DependencyNode }
  | { kind: "edge"; edge: DependencyEdge }
  | null;

function pollingInterval(autoRefresh: boolean) {
  return (query: { state: { fetchFailureCount: number } }) => {
    if (!autoRefresh) return false;
    const failures = Math.max(0, query.state.fetchFailureCount);
    return Math.min(3_000 * 2 ** Math.min(failures, 4), 60_000);
  };
}

function errorDescription(error: unknown) {
  if (error instanceof ApiError && error.status === 403) return "接口拒绝了当前权限请求。";
  if (error instanceof ApiError && error.status === 401) return "登录状态已失效。";
  return "接口暂时不可用；页面会在退避后重试。";
}

function timeLabel(value: string | number | null | undefined) {
  if (typeof value === "number") {
    if (!Number.isFinite(value) || value <= 0) return "—";
    return new Date(value).toLocaleString();
  }
  if (typeof value !== "string" || !Number.isFinite(Date.parse(value))) return "—";
  return new Date(value).toLocaleString();
}

function formatBytes(value: number | null | undefined) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) return "—";
  if (value < 1024) return value.toLocaleString() + " B";
  const units = ["KB", "MB", "GB", "TB"];
  let size = value / 1024;
  let index = 0;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index += 1;
  }
  return size.toFixed(1) + " " + units[index];
}

function percent(value: number | null | undefined) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) return "—";
  return (Math.min(1, value) * 100).toFixed(1) + "%";
}

function statusColor(value: unknown): string | undefined {
  switch (normalizeStatus(value)) {
    case "UP":
    case "RUNNING":
    case "ENABLED":
      return "success";
    case "FAILED":
    case "ERROR":
      return "error";
    case "STOPPED":
    case "DISABLED":
    case "UNINSTALLED":
      return "warning";
    default:
      return undefined;
  }
}

function booleanLabel(value: boolean | null | undefined) {
  if (value === true) return "是";
  if (value === false) return "否";
  return "—";
}

function humanMethod(value: string) {
  const labels: Record<string, string> = {
    PASSWORD: "密码",
    EMAIL_OTP: "邮箱验证码",
    SMS_OTP: "短信验证码",
    PASSKEY: "通行密钥",
    WECHAT: "微信",
  };
  return labels[value] ?? value;
}

function QueryNotice<T>({
  query,
  allowed,
  title,
}: {
  query: QueryView<T>;
  allowed: boolean;
  title: string;
}) {
  if (!allowed) {
    return <Alert type="info" showIcon message="无权限" description={title + "需要对应的宿主全局读取权限。"} />;
  }
  if (query.isPending && query.data === undefined) {
    return <Alert type="info" showIcon message="正在读取" description={title + "尚未返回数据。"} />;
  }
  if (query.isError) {
    return (
      <Alert
        type="warning"
        showIcon
        message={query.data === undefined ? "当前数据不可用" : "显示最近一次成功读取的数据"}
        description={
          <Space direction="vertical" size={2}>
            <span>{errorDescription(query.error)}</span>
            <span>最近成功读取：{query.dataUpdatedAt ? timeLabel(query.dataUpdatedAt) : "—"}</span>
          </Space>
        }
      />
    );
  }
  if (query.data === undefined) {
    return <Alert type="info" showIcon message="数据未知" description={title + "没有返回可展示的数据。"} />;
  }
  return null;
}

function MetricCard({
  title,
  value,
  detail,
  status,
}: {
  title: string;
  value: string;
  detail?: string;
  status?: string | null;
}) {
  return (
    <Card size="small" className="runtime-observer-metric">
      <Statistic title={title} value={value} />
      <div className="runtime-observer-metric-detail">
        {status !== undefined && <Tag color={statusColor(status)}>{normalizeStatus(status)}</Tag>}
        {detail && <Typography.Text type="secondary">{detail}</Typography.Text>}
      </div>
    </Card>
  );
}

function MetricGrid({ snapshot }: { snapshot?: MonitoringSnapshot }) {
  const summary = snapshot?.summary;
  const machine = snapshot?.machine;
  const database = snapshot?.database;
  const heapPercent = machine?.heapMaxBytes && machine.heapMaxBytes > 0 && typeof machine.heapUsedBytes === "number"
    ? machine.heapUsedBytes / machine.heapMaxBytes
    : null;
  return (
    <Row gutter={[12, 12]}>
      <Col xs={24} sm={12} lg={8} xl={6}>
        <MetricCard title="已启用模块" value={summary ? formatMetric(summary.enabledModules) + " / " + formatMetric(summary.totalModules) : "—"} detail="已启用 / 模块总数" />
      </Col>
      <Col xs={24} sm={12} lg={8} xl={6}>
        <MetricCard
          title="数据库连接池"
          value={database?.status ?? "—"}
          detail={"活跃 " + formatMetric(database?.activeConnections) + " · 空闲 " + formatMetric(database?.idleConnections) + " · 上限 " + formatMetric(database?.maxConnections)}
          status={database?.status}
        />
      </Col>
      <Col xs={24} sm={12} lg={8} xl={6}>
        <MetricCard title="进程 CPU" value={percent(machine?.processCpuUsage)} detail={"系统 CPU " + percent(machine?.systemCpuUsage)} />
      </Col>
      <Col xs={24} sm={12} lg={8} xl={6}>
        <MetricCard title="JVM 堆" value={formatBytes(machine?.heapUsedBytes)} detail={"上限 " + formatBytes(machine?.heapMaxBytes) + " · " + percent(heapPercent)} />
      </Col>
      <Col xs={24} sm={12} lg={8} xl={6}>
        <MetricCard title="在线会话" value={summary ? formatMetric(summary.activeSessions) : "—"} detail={summary ? "活跃账号 " + formatMetric(summary.activeUsers) : "监控摘要未返回"} />
      </Col>
      <Col xs={24} sm={12} lg={8} xl={6}>
        <MetricCard title="Outbox 等待 / 处理中" value={summary ? formatMetric(summary.pendingOutbox) + " / " + formatMetric(summary.processingOutbox) : "—"} detail="待处理队列与租约处理中" />
      </Col>
      <Col xs={24} sm={12} lg={8} xl={6}>
        <MetricCard title="Outbox 失败 / 死信" value={summary ? formatMetric(summary.failedOutbox) + " / " + formatMetric(summary.deadLetterOutbox) : "—"} detail={summary ? "待重试 " + formatMetric(summary.retryPendingOutbox) + " · 超期 " + formatMetric(summary.overdueOutbox) : "监控摘要未返回"} />
      </Col>
      <Col xs={24} sm={12} lg={8} xl={6}>
        <MetricCard title="短信队列" value={summary ? formatMetric(summary.pendingSms) + " / " + formatMetric(summary.failedSms) : "—"} detail="待发送 / 失败" />
      </Col>
      <Col xs={24} sm={12} lg={8} xl={6}>
        <MetricCard title="邮件队列" value={summary ? formatMetric(summary.pendingMail) + " / " + formatMetric(summary.failedMail) : "—"} detail="待发送 / 失败" />
      </Col>
      <Col xs={24} sm={12} lg={8} xl={6}>
        <MetricCard title="最近 5 分钟事件" value={summary ? formatMetric(summary.eventsCreatedLast5m) + " / " + formatMetric(summary.eventsDeliveredLast5m) : "—"} detail="已创建 / 已投递" />
      </Col>
    </Row>
  );
}

function SampleChart({ samples }: { samples: ObserverSample[] }) {
  const { token } = theme.useToken();
  const values = samples.flatMap(sample => [
    sample.pendingOutbox,
    sample.processingOutbox,
    sample.failedOutbox,
    sample.deadLetterOutbox,
  ]).filter((value): value is number => typeof value === "number" && Number.isFinite(value));
  if (samples.length === 0 || values.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="已有测量点后显示本页采样趋势" />;
  }
  const width = Math.max(520, 22 * Math.max(1, samples.length - 1) + 48);
  const height = 180;
  const paddingX = 24;
  const paddingY = 22;
  const minValue = Math.min(0, ...values);
  const maxValue = Math.max(1, ...values);
  const xAt = (index: number) => paddingX + (samples.length < 2 ? (width - 2 * paddingX) / 2 : index * (width - 2 * paddingX) / (samples.length - 1));
  const yAt = (value: number) => height - paddingY - (value - minValue) / Math.max(1, maxValue - minValue) * (height - 2 * paddingY);
  const series = [
    { key: "pendingOutbox" as const, label: "待处理", color: token.colorPrimary },
    { key: "processingOutbox" as const, label: "处理中", color: token.colorSuccess },
    { key: "failedOutbox" as const, label: "失败", color: token.colorError },
    { key: "deadLetterOutbox" as const, label: "死信", color: token.colorWarning },
  ];
  const pathFor = (key: typeof series[number]["key"]) => {
    let path = "";
    let segmentStart = true;
    samples.forEach((sample, index) => {
      const value = sample[key];
      if (typeof value !== "number" || !Number.isFinite(value)) {
        segmentStart = true;
        return;
      }
      path += (segmentStart ? "M " : "L ") + xAt(index) + " " + yAt(value) + " ";
      segmentStart = false;
    });
    return path.trim();
  };
  return (
    <div className="runtime-observer-chart-scroll">
      <svg width={width} height={height} viewBox={"0 0 " + width + " " + height} role="img" aria-label="本页 Outbox 队列采样趋势">
        <line x1={paddingX} y1={height - paddingY} x2={width - paddingX} y2={height - paddingY} stroke={token.colorBorderSecondary} />
        {series.map(item => <path key={item.key} d={pathFor(item.key)} fill="none" stroke={item.color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />)}
        {samples.map((sample, index) => (
          <g key={sample.measuredAt}>
            {series.map(item => {
              const value = sample[item.key];
              if (typeof value !== "number" || !Number.isFinite(value)) return null;
              return <circle key={item.key} cx={xAt(index)} cy={yAt(value)} r={3} fill={item.color} />;
            })}
          </g>
        ))}
      </svg>
      <div className="runtime-observer-chart-legend">
        {series.map(item => <span key={item.key} style={{ color: item.color }}>{item.label}</span>)}
        <Typography.Text type="secondary">本页采样，不保存历史 · 已采样 {samples.length} 点，上限 30</Typography.Text>
      </div>
    </div>
  );
}

function EventFlow({ snapshot }: { snapshot?: MonitoringSnapshot }) {
  const flow = snapshot?.flow;
  const queues = snapshot?.summary;
  const missing = flow?.missingConsumers ?? [];
  if (!flow) {
    return <Alert type="info" showIcon message="事件拓扑数据未知" description="监控接口没有返回生产者/消费者类型覆盖数据。" />;
  }
  return (
    <Space direction="vertical" size={14} style={{ display: "flex" }}>
      <div className="runtime-observer-event-flow" aria-label="事件类型覆盖与队列状态">
        <div className="runtime-observer-flow-step">
          <Typography.Text type="secondary">已声明生产类型</Typography.Text>
          <Typography.Title level={4}>{formatMetric(flow.producerTypes)}</Typography.Title>
        </div>
        <div className="runtime-observer-flow-arrow" aria-hidden="true">→</div>
        <div className="runtime-observer-flow-step">
          <Typography.Text type="secondary">Outbox 队列</Typography.Text>
          <Space wrap>
            <Tag>待处理 {formatMetric(queues?.pendingOutbox)}</Tag>
            <Tag>处理中 {formatMetric(queues?.processingOutbox)}</Tag>
            <Tag color={queues?.failedOutbox ? "error" : undefined}>失败 {formatMetric(queues?.failedOutbox)}</Tag>
            <Tag color={queues?.deadLetterOutbox ? "error" : undefined}>死信 {formatMetric(queues?.deadLetterOutbox)}</Tag>
          </Space>
        </div>
        <div className="runtime-observer-flow-arrow" aria-hidden="true">→</div>
        <div className="runtime-observer-flow-step">
          <Typography.Text type="secondary">已声明消费者类型</Typography.Text>
          <Typography.Title level={4}>{formatMetric(flow.consumerTypes)}</Typography.Title>
        </div>
      </div>
      <Space wrap>
        <Tag color={statusColor(flow.status)}>{normalizeStatus(flow.status)}</Tag>
        <Typography.Text>已连接类型：{formatMetric(flow.connectedTypes)}</Typography.Text>
        <Typography.Text>未匹配生产者的消费类型：{formatMetric(flow.unmatchedConsumerTypes)}</Typography.Text>
        <Typography.Text>5 分钟创建 / 投递：{formatMetric(flow.createdLast5m)} / {formatMetric(flow.deliveredLast5m)}</Typography.Text>
        <Typography.Text>待重试 / 超期：{formatMetric(flow.retryPending)} / {formatMetric(flow.overdue)}</Typography.Text>
      </Space>
      <div>
        <Typography.Text strong>缺少消费者的来源事件类型</Typography.Text>
        <div className="runtime-observer-missing-types">
          {missing.length > 0
            ? missing.map((item, index) => (
              <Tag key={(item.sourceType ?? "unknown") + ":" + (item.eventType ?? "unknown") + ":" + index}>
                {(item.sourceType ?? "未知来源") + " → " + (item.eventType ?? "未知事件")}
              </Tag>
            ))
            : <Typography.Text type="secondary">{flow.missingConsumers ? "当前未报告缺失事件类型。" : "接口未提供缺失事件类型清单。"}</Typography.Text>}
        </div>
      </div>
      <Typography.Text type="secondary">拓扑只报告类型数量和缺失消费者类型；宿主未提供逐消费者连线或处理耗时。</Typography.Text>
    </Space>
  );
}

function eventTime(record: EventRecord) {
  return timeLabel(record.createdAt);
}

const eventColumns: TableColumnsType<EventRecord> = [
  { title: "来源", dataIndex: "sourceType", key: "sourceType", width: 150, render: value => value || "—" },
  { title: "事件类型", dataIndex: "eventType", key: "eventType", width: 200, render: value => value || "—" },
  { title: "状态", dataIndex: "status", key: "status", width: 120, render: value => <Tag color={statusColor(value)}>{normalizeStatus(value)}</Tag> },
  { title: "尝试次数", dataIndex: "attempts", key: "attempts", width: 100, render: value => formatMetric(value) },
  { title: "创建时间", dataIndex: "createdAt", key: "createdAt", width: 190, render: (_value, record) => eventTime(record) },
];

function EventTable({
  monitoring,
  outbox,
  canReadMonitoring,
  canReadEvents,
}: {
  monitoring?: MonitoringSnapshot;
  outbox?: EventOutboxPage;
  canReadMonitoring: boolean;
  canReadEvents: boolean;
}) {
  const useOutbox = canReadEvents && (outbox?.items?.length ?? 0) > 0;
  const rows = useOutbox ? outbox?.items ?? [] : canReadMonitoring ? monitoring?.flow?.recentEvents ?? [] : [];
  const sourceLabel = useOutbox
    ? "事件队列读取接口"
    : canReadMonitoring && monitoring?.flow?.recentEvents ? "监控摘要中的最近 8 条事件" : "未提供事件记录";
  if (!canReadEvents && !canReadMonitoring) {
    return <Alert type="info" showIcon message="无权限" description="最近事件需要监控或事件队列的全局读取权限。" />;
  }
  if (rows.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前接口没有返回事件记录" />;
  }
  return (
    <Space direction="vertical" size={8} style={{ display: "flex" }}>
      <Typography.Text type="secondary">{sourceLabel} · 最多展示 8 条元数据</Typography.Text>
      <div className="runtime-observer-table-scroll">
        <Table<EventRecord>
          size="small"
          pagination={false}
          rowKey={(record, index) => record.id || [record.sourceType, record.eventType, record.createdAt, index].join(":")}
          columns={eventColumns}
          dataSource={rows.slice(0, 8)}
          scroll={{ x: 760 }}
        />
      </div>
    </Space>
  );
}

function moduleStatus(record: MergedModuleRecord) {
  return record.status;
}

function ModuleCard({ record, onOpen }: { record: MergedModuleRecord; onOpen: () => void }) {
  const status = moduleStatus(record);
  return (
    <Card
      size="small"
      hoverable
      role="button"
      tabIndex={0}
      aria-label={"查看模块 " + (record.name ?? record.id)}
      onClick={onOpen}
      onKeyDown={event => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onOpen();
        }
      }}
      className="runtime-observer-module-card"
    >
      <Space direction="vertical" size={6} style={{ display: "flex" }}>
        <Space wrap align="start" style={{ justifyContent: "space-between" }}>
          <Typography.Text strong>{record.name ?? record.id}</Typography.Text>
          <Tag color={statusColor(status)}>{status ?? "运行状态未知"}</Tag>
        </Space>
        <Typography.Text type="secondary">{record.id} · {record.version ?? "版本未知"}</Typography.Text>
        <Space wrap size={4}>
          <Tag>启用：{booleanLabel(record.enabled)}</Tag>
          {record.plugin && <Tag>安装：{booleanLabel(record.installed)}</Tag>}
          <Tag>依赖：{record.dependencies.length}</Tag>
        </Space>
      </Space>
    </Card>
  );
}

function shortText(value: string, limit = 22) {
  return value.length > limit ? value.slice(0, limit - 1) + "…" : value;
}

function DependencyGraph({
  records,
  onSelect,
}: {
  records: MergedModuleRecord[];
  onSelect: (selection: Selection) => void;
}) {
  const { token } = theme.useToken();
  const graph = useMemo(() => buildDependencyGraph(records), [records]);
  if (graph.nodes.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="模块接口没有返回可绘制的依赖节点" />;
  }
  return (
    <div className="runtime-observer-graph-scroll">
      <svg width={graph.width} height={graph.height} viewBox={"0 0 " + graph.width + " " + graph.height} role="img" aria-label="模块依赖图，箭头从依赖指向使用方">
        <defs>
          <marker id="runtime-observer-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 z" fill={token.colorTextTertiary} />
          </marker>
        </defs>
        {graph.edges.map(edge => {
          const startX = edge.source.x + edge.source.width;
          const startY = edge.source.y + edge.source.height / 2;
          const endX = edge.target.x;
          const endY = edge.target.y + edge.target.height / 2;
          const middleX = (startX + endX) / 2;
          const path = "M " + startX + " " + startY + " C " + middleX + " " + startY + ", " + middleX + " " + endY + ", " + endX + " " + endY;
          const select = () => onSelect({ kind: "edge", edge });
          return (
            <g
              key={edge.id}
              role="button"
              tabIndex={0}
              aria-label={"依赖关系 " + edge.from + " 指向 " + edge.to}
              onClick={select}
              onKeyDown={event => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  select();
                }
              }}
              style={{ cursor: "pointer" }}
            >
              <title>{edge.from + " → " + edge.to}</title>
              <path d={path} fill="none" stroke="transparent" strokeWidth={14} />
              <path d={path} fill="none" stroke={token.colorTextTertiary} strokeWidth={1.5} markerEnd="url(#runtime-observer-arrow)" pointerEvents="none" />
            </g>
          );
        })}
        {graph.nodes.map(node => {
          const select = () => onSelect({ kind: "node", node });
          return (
            <g
              key={node.id}
              transform={"translate(" + node.x + " " + node.y + ")"}
              role="button"
              tabIndex={0}
              aria-label={"查看依赖节点 " + node.label}
              onClick={select}
              onKeyDown={event => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  select();
                }
              }}
              style={{ cursor: "pointer" }}
            >
              <title>{node.known ? node.label + " · " + node.id : node.label + " · 状态未知"}</title>
              <rect
                width={node.width}
                height={node.height}
                rx={10}
                fill={token.colorBgElevated}
                stroke={node.known ? token.colorBorderSecondary : token.colorWarning}
                strokeWidth={node.known ? 1 : 1.5}
                strokeDasharray={node.known ? undefined : "5 4"}
              />
              <text x={12} y={26} fontSize={12} fontWeight={600} fill={token.colorText}>{shortText(node.label)}</text>
              <text x={12} y={48} fontSize={10} fill={token.colorTextSecondary}>{shortText(node.id, 25)}</text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

function ModuleDetails({
  selection,
  records,
  onClose,
}: {
  selection: Selection;
  records: MergedModuleRecord[];
  onClose: () => void;
}) {
  const graph = useMemo(() => buildDependencyGraph(records), [records]);
  const selectedNode = selection?.kind === "node"
    ? graph.nodes.find(node => node.id === selection.node.id) ?? null
    : null;
  const selectedRecord = selectedNode?.record ?? null;
  const selectedEdge = selection?.kind === "edge"
    ? graph.edges.find(edge => edge.id === selection.edge.id) ?? null
    : null;
  const selectedDependency = selectedEdge ? records.find(record => record.id === selectedEdge.from) ?? null : null;
  const selectedDependent = selectedEdge ? records.find(record => record.id === selectedEdge.to) ?? null : null;
  useEffect(() => {
    if ((selection?.kind === "node" && !selectedNode) || (selection?.kind === "edge" && !selectedEdge)) onClose();
  }, [onClose, selectedEdge, selectedNode, selection]);
  return (
    <Drawer
      open={selectedNode !== null || selectedEdge !== null}
      title={selectedEdge ? "依赖关系" : selectedNode?.label ?? "模块详情"}
      onClose={onClose}
      width="min(560px, calc(100vw - 24px))"
    >
      {selectedEdge ? (
        <Descriptions bordered size="small" column={1}>
          <Descriptions.Item label="依赖项">{selectedDependency?.name ?? selectedEdge.from}</Descriptions.Item>
          <Descriptions.Item label="依赖项 ID">{selectedEdge.from}</Descriptions.Item>
          <Descriptions.Item label="使用方">{selectedDependent?.name ?? selectedEdge.to}</Descriptions.Item>
          <Descriptions.Item label="使用方 ID">{selectedEdge.to}</Descriptions.Item>
          <Descriptions.Item label="方向">{selectedEdge.from + " → " + selectedEdge.to}</Descriptions.Item>
          <Descriptions.Item label="来源">宿主模块依赖字段或插件清单</Descriptions.Item>
        </Descriptions>
      ) : selectedNode && !selectedNode.known ? (
        <Alert type="warning" showIcon message="未知依赖节点" description={"依赖清单引用了 " + selectedNode.id + "，但模块/插件接口没有返回该节点的状态。"} />
      ) : selectedNode ? (
        <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }}>
          <Descriptions.Item label="名称">{selectedRecord?.name ?? selectedNode.label}</Descriptions.Item>
          <Descriptions.Item label="ID">{selectedNode.id}</Descriptions.Item>
          <Descriptions.Item label="版本">{selectedRecord?.version ?? "—"}</Descriptions.Item>
          <Descriptions.Item label="启用">{booleanLabel(selectedRecord?.enabled)}</Descriptions.Item>
          <Descriptions.Item label="已安装">{booleanLabel(selectedRecord?.installed)}</Descriptions.Item>
          <Descriptions.Item label="运行状态"><Tag color={statusColor(selectedRecord?.status)}>{selectedRecord?.status ?? "未知"}</Tag></Descriptions.Item>
          <Descriptions.Item label="核心模块">{booleanLabel(selectedRecord?.core)}</Descriptions.Item>
          <Descriptions.Item label="必需模块">{booleanLabel(selectedRecord?.required)}</Descriptions.Item>
          <Descriptions.Item label="状态版本">{formatMetric(selectedRecord?.stateVersion)}</Descriptions.Item>
          <Descriptions.Item label="依赖" span={2}>{selectedRecord?.dependencies.length ? selectedRecord.dependencies.join("、") : "无已声明依赖"}</Descriptions.Item>
          {selectedRecord?.plugin && (
            <>
              <Descriptions.Item label="插件制品状态">{selectedRecord.plugin.status ?? "未知"}</Descriptions.Item>
              <Descriptions.Item label="显式安装">{booleanLabel(selectedRecord.plugin.explicitInstall)}</Descriptions.Item>
              <Descriptions.Item label="制品摘要" span={2}>{selectedRecord.plugin.digest ?? "—"}</Descriptions.Item>
            </>
          )}
        </Descriptions>
      ) : null}
    </Drawer>
  );
}

function ModulesPanel({
  modules,
  plugins,
  allowed,
  moduleQuery,
  pluginQuery,
  onSelect,
}: {
  modules?: ModuleRecord[];
  plugins?: PluginRecord[];
  allowed: boolean;
  moduleQuery: QueryView<ModuleRecord[]>;
  pluginQuery: QueryView<PluginRecord[]>;
  onSelect: (selection: Selection) => void;
}) {
  const records = useMemo(
    () => mergeModuleRecords(modules ?? [], pluginQuery.isError ? [] : plugins ?? []),
    [modules, pluginQuery.isError, plugins],
  );
  return (
    <Space direction="vertical" size={14} style={{ display: "flex" }}>
      <QueryNotice query={moduleQuery} allowed={allowed} title="模块清单" />
      {allowed && moduleQuery.data && (
        <>
          {pluginQuery.isError && <Alert type="info" showIcon message="插件制品状态不可用" description="依赖图保留模块清单数据；安装状态显示为未知。"/>}
          {records.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="模块接口没有返回记录" />
          ) : (
            <>
              <Typography.Text type="secondary">箭头从依赖项指向使用方；点击节点或连线查看来源字段。</Typography.Text>
              <DependencyGraph records={records} onSelect={onSelect} />
              <Divider titlePlacement="start">模块与插件</Divider>
              <Row gutter={[10, 10]}>
                {records.map(record => (
                  <Col key={record.id} xs={24} sm={12} lg={8} xl={6}>
                    <ModuleCard
                      record={record}
                      onOpen={() => {
                        const node = buildDependencyGraph(records).nodes.find(candidate => candidate.id === record.id);
                        if (node) onSelect({ kind: "node", node });
                      }}
                    />
                  </Col>
                ))}
              </Row>
            </>
          )}
        </>
      )}
    </Space>
  );
}

function AuthFlow({ methods, registration }: { methods?: AuthMethods; registration?: RegistrationConfig }) {
  const authMethods = methods?.methods ?? [];
  const registrationKnown = typeof methods?.registration?.available === "boolean"
    && typeof registration?.available === "boolean";
  const registrationAvailable = registrationKnown
    ? methods?.registration?.available === true && registration?.available === true
    : null;
  const loginMethodText = authMethods.length > 0 ? authMethods.map(humanMethod).join("、") : methods?.methods ? "未声明可用方式" : "未知";
  const registrationChannels = [...new Set([...(methods?.registration?.channels ?? []), ...(registration?.channels ?? [])])];
  const captcha = methods?.captcha;
  const captchaActions = captcha?.actions ?? [];
  const requiredActions = captcha?.alwaysActions ?? [];
  return (
    <Space direction="vertical" size={16} style={{ display: "flex" }}>
      <div className="runtime-observer-auth-flow">
        <div className="runtime-observer-auth-step">
          <Typography.Text type="secondary">登录</Typography.Text>
          <Typography.Text strong>{loginMethodText}</Typography.Text>
          <Typography.Text type="secondary">公开方法配置</Typography.Text>
        </div>
        <div className="runtime-observer-auth-step">
          <Typography.Text type="secondary">注册</Typography.Text>
          <Typography.Text strong>{registrationAvailable === null ? "未知" : registrationAvailable ? "开放" : "关闭"}</Typography.Text>
          <Typography.Text type="secondary">{registrationChannels.length ? "渠道：" + registrationChannels.join("、") : "未返回注册渠道"}</Typography.Text>
        </div>
        <div className="runtime-observer-auth-step">
          <Typography.Text type="secondary">找回账号</Typography.Text>
          <Typography.Text strong>使用宿主恢复流程</Typography.Text>
          <Typography.Text type="secondary">公开接口未提供个人请求状态</Typography.Text>
        </div>
        <div className="runtime-observer-auth-step">
          <Typography.Text type="secondary">手机号绑定</Typography.Text>
          <Typography.Text strong>主认证后验证手机号</Typography.Text>
          <Typography.Text type="secondary">此面板不读取个人绑定记录</Typography.Text>
        </div>
        <div className="runtime-observer-auth-step">
          <Typography.Text type="secondary">敏感操作重验</Typography.Text>
          <Typography.Text strong>主认证 + 按策略要求 MFA</Typography.Text>
          <Typography.Text type="secondary">不展示账号级认证轨迹</Typography.Text>
        </div>
      </div>
      <Row gutter={[12, 12]}>
        <Col xs={24} lg={12}>
          <Card size="small" title="注册配置">
            <Descriptions size="small" column={1}>
              <Descriptions.Item label="公共方法支持">{booleanLabel(methods?.registration?.supported)}</Descriptions.Item>
              <Descriptions.Item label="方法可用">{booleanLabel(methods?.registration?.available)}</Descriptions.Item>
              <Descriptions.Item label="注册配置开放">{booleanLabel(registration?.available)}</Descriptions.Item>
              <Descriptions.Item label="注册渠道">{registrationChannels.length ? registrationChannels.join("、") : "—"}</Descriptions.Item>
              <Descriptions.Item label="配置原因">{registration?.reason ?? "—"}</Descriptions.Item>
              <Descriptions.Item label="策略版本">{formatMetric(registration?.policyVersion)}</Descriptions.Item>
              <Descriptions.Item label="验证码有效期">{registration?.codePolicy?.expiresInSeconds == null ? "—" : registration.codePolicy.expiresInSeconds + " 秒"}</Descriptions.Item>
              <Descriptions.Item label="重发冷却">{registration?.codePolicy?.cooldownSeconds == null ? "—" : registration.codePolicy.cooldownSeconds + " 秒"}</Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card size="small" title="验证码策略">
            <Descriptions size="small" column={1}>
              <Descriptions.Item label="验证码能力">{booleanLabel(methods?.capabilities?.captcha)}</Descriptions.Item>
              <Descriptions.Item label="需要验证码">{booleanLabel(captcha?.required)}</Descriptions.Item>
              <Descriptions.Item label="类型">{captcha?.type ?? "—"}</Descriptions.Item>
              <Descriptions.Item label="可配置动作">{captchaActions.length ? captchaActions.join("、") : "—"}</Descriptions.Item>
              <Descriptions.Item label="始终要求">{requiredActions.length ? requiredActions.join("、") : "—"}</Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
      </Row>
      <Alert
        type="info"
        showIcon
        message="公开策略视图，不是实时认证跟踪"
        description="此处只读取公开认证方式和注册配置。登录、注册验证码、找回密码、手机号绑定及敏感操作重验仍由宿主认证流程处理；个人挑战、手机号和会话明细不会进入此面板。"
      />
    </Space>
  );
}

export function RuntimeObserverPage({
  canRead,
  canReadMonitoring,
  canListModules,
  canReadEvents,
  readOnlyPreview,
}: Props) {
  const { token } = theme.useToken();
  const client = useQueryClient();
  const operationSignal = useSessionScope();
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [scopeInvalidated, setScopeInvalidated] = useState(false);
  const [samples, setSamples] = useState<ObserverSample[]>([]);
  const [selection, setSelection] = useState<Selection>(null);
  const isPrivateEnabled = canRead && !readOnlyPreview && !scopeInvalidated;
  const polling = pollingInterval(autoRefresh && !readOnlyPreview);
  const monitoringAllowed = isPrivateEnabled && canReadMonitoring;
  const modulesAllowed = isPrivateEnabled && canListModules;
  const eventsAllowed = isPrivateEnabled && canReadEvents;
  const publicPolicyAllowed = canRead && !scopeInvalidated;

  const monitoring = useQuery({
    queryKey: [...QUERY_ROOT, "monitoring"],
    queryFn: ({ signal }) => api<MonitoringSnapshot>("/platform/monitoring/summary", { signal }),
    enabled: monitoringAllowed,
    refetchInterval: polling,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    retry: false,
  });
  const modules = useQuery({
    queryKey: [...QUERY_ROOT, "modules"],
    queryFn: ({ signal }) => api<ModuleRecord[]>("/platform/modules", { signal }),
    enabled: modulesAllowed,
    refetchInterval: polling,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    retry: false,
  });
  const plugins = useQuery({
    queryKey: [...QUERY_ROOT, "plugins"],
    queryFn: ({ signal }) => api<PluginRecord[]>("/platform/modules/plugins", { signal }),
    enabled: modulesAllowed,
    refetchInterval: polling,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    retry: false,
  });
  const outbox = useQuery({
    queryKey: [...QUERY_ROOT, "event-outbox"],
    queryFn: ({ signal }) => api<EventOutboxPage>("/platform/event-outbox?page=1&pageSize=8", { signal }),
    enabled: eventsAllowed,
    refetchInterval: polling,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    retry: false,
  });
  const authMethods = useQuery({
    queryKey: [...QUERY_ROOT, "auth-methods"],
    queryFn: ({ signal }) => api<AuthMethods>("/auth/methods", { signal }),
    enabled: publicPolicyAllowed,
    refetchInterval: polling,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    retry: false,
  });
  const registration = useQuery({
    queryKey: [...QUERY_ROOT, "registration-config"],
    queryFn: ({ signal }) => api<RegistrationConfig>("/auth/registration/config", { signal }),
    enabled: publicPolicyAllowed,
    refetchInterval: polling,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    retry: false,
  });

  const clearOwnedQueries = useCallback(() => {
    void client.cancelQueries({ queryKey: QUERY_ROOT });
    client.removeQueries({ queryKey: QUERY_ROOT });
  }, [client]);

  useEffect(() => {
    let disposed = false;
    const signal = operationSignal();
    const invalidate = () => {
      queueMicrotask(() => {
        if (disposed) return;
        setScopeInvalidated(true);
        setSamples([]);
        setSelection(null);
        clearOwnedQueries();
      });
    };
    if (signal.aborted) invalidate();
    else signal.addEventListener("abort", invalidate, { once: true });
    return () => {
      disposed = true;
      signal.removeEventListener("abort", invalidate);
    };
  }, [clearOwnedQueries, operationSignal]);

  useEffect(() => () => {
    void client.cancelQueries({ queryKey: QUERY_ROOT });
    client.removeQueries({ queryKey: QUERY_ROOT });
  }, [client]);

  useEffect(() => {
    if (!isPrivateEnabled) {
      setSamples([]);
      setSelection(null);
      void client.cancelQueries({ queryKey: QUERY_ROOT });
      client.removeQueries({ queryKey: QUERY_ROOT });
    }
  }, [client, isPrivateEnabled]);

  useEffect(() => {
    if (!monitoringAllowed) {
      setSamples([]);
      const key = [...QUERY_ROOT, "monitoring"];
      void client.cancelQueries({ queryKey: key });
      client.removeQueries({ queryKey: key });
    }
  }, [client, monitoringAllowed]);

  useEffect(() => {
    if (!modulesAllowed) {
      setSelection(null);
      for (const key of [[...QUERY_ROOT, "modules"], [...QUERY_ROOT, "plugins"]]) {
        void client.cancelQueries({ queryKey: key });
        client.removeQueries({ queryKey: key });
      }
    }
  }, [client, modulesAllowed]);

  useEffect(() => {
    if (!eventsAllowed) {
      const key = [...QUERY_ROOT, "event-outbox"];
      void client.cancelQueries({ queryKey: key });
      client.removeQueries({ queryKey: key });
    }
  }, [client, eventsAllowed]);

  useEffect(() => {
    if (!monitoringAllowed || !monitoring.data) return;
    setSamples(current => appendSample(current, monitoring.data, 30));
  }, [monitoring.data, monitoringAllowed]);

  const moduleRecords = useMemo(
    () => mergeModuleRecords(
      modulesAllowed ? modules.data ?? [] : [],
      modulesAllowed && !plugins.isError ? plugins.data ?? [] : [],
    ),
    [modules.data, modulesAllowed, plugins.data, plugins.isError],
  );
  const requestsInFlight = monitoring.isFetching || modules.isFetching || plugins.isFetching || outbox.isFetching
    || authMethods.isFetching || registration.isFetching;
  const lastMeasuredAt = monitoringAllowed ? monitoring.data?.summary?.measuredAt : null;

  const refresh = useCallback(async () => {
    const requests: Array<Promise<unknown>> = [];
    if (monitoringAllowed) requests.push(monitoring.refetch());
    if (modulesAllowed) requests.push(modules.refetch(), plugins.refetch());
    if (eventsAllowed) requests.push(outbox.refetch());
    if (publicPolicyAllowed) requests.push(authMethods.refetch(), registration.refetch());
    await Promise.allSettled(requests);
  }, [authMethods, eventsAllowed, modules, modulesAllowed, monitoring, monitoringAllowed, outbox, plugins, publicPolicyAllowed, registration]);

  const pageCss = [
    ".runtime-observer{display:flex;flex-direction:column;gap:16px;min-width:0}",
    ".runtime-observer-toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}",
    ".runtime-observer-metric{height:100%}",
    ".runtime-observer-metric .ant-statistic-content{overflow-wrap:anywhere}",
    ".runtime-observer-metric-detail{display:flex;align-items:center;gap:6px;flex-wrap:wrap;min-height:24px;margin-top:8px}",
    ".runtime-observer-graph-scroll,.runtime-observer-chart-scroll,.runtime-observer-table-scroll{max-width:100%;overflow:auto;overscroll-behavior:contain}",
    ".runtime-observer-graph-scroll{max-height:520px}",
    ".runtime-observer-graph-scroll svg,.runtime-observer-chart-scroll svg{display:block}",
    ".runtime-observer-chart-legend{display:flex;align-items:center;gap:14px;flex-wrap:wrap;margin-top:8px}",
    ".runtime-observer-chart-legend span{font-size:12px;font-weight:600}",
    ".runtime-observer-missing-types{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}",
    ".runtime-observer-event-flow{display:grid;grid-template-columns:minmax(130px,1fr) 30px minmax(180px,1.4fr) 30px minmax(130px,1fr);gap:8px;align-items:stretch}",
    ".runtime-observer-flow-step{border:1px solid " + token.colorBorderSecondary + ";border-radius:10px;padding:12px;display:flex;flex-direction:column;gap:8px;min-width:0}",
    ".runtime-observer-flow-step .ant-typography{margin:0}",
    ".runtime-observer-flow-arrow{display:grid;place-items:center;font-size:22px;color:" + token.colorTextSecondary + "}",
    ".runtime-observer-auth-flow{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px}",
    ".runtime-observer-auth-step{border:1px solid " + token.colorBorderSecondary + ";border-radius:10px;padding:12px;display:flex;flex-direction:column;gap:7px;min-width:0}",
    ".runtime-observer-auth-step .ant-typography{margin:0;overflow-wrap:anywhere}",
    ".runtime-observer-module-card{height:100%;cursor:pointer}",
    ".runtime-observer-module-card:focus-visible{outline:2px solid " + token.colorPrimary + ";outline-offset:2px}",
    "@media(max-width:1199px){.runtime-observer-auth-flow{grid-template-columns:repeat(2,minmax(0,1fr))}}",
    "@media(max-width:767px){.runtime-observer-event-flow{grid-template-columns:minmax(0,1fr)}.runtime-observer-flow-arrow{transform:rotate(90deg);min-height:24px}.runtime-observer-auth-flow{grid-template-columns:minmax(0,1fr)}}",
  ].join("\n");

  return (
    <div className="runtime-observer">
      <style>{pageCss}</style>
      <div className="runtime-observer-toolbar">
        <Space wrap>
          <Typography.Text type="secondary">
            {readOnlyPreview ? "角色预览中隐藏运行数据。" : "前台轮询 · 默认 3 秒，失败时逐步退避"}
          </Typography.Text>
          <Typography.Text type="secondary">最近测量：{timeLabel(lastMeasuredAt)}</Typography.Text>
          {requestsInFlight && <Tag>正在刷新</Tag>}
          {scopeInvalidated && <Tag color="warning">认证上下文已变化</Tag>}
        </Space>
        <Space wrap>
          <Button onClick={() => void refresh()} disabled={!canRead || scopeInvalidated || readOnlyPreview} loading={requestsInFlight}>手动刷新</Button>
          <Button onClick={() => setAutoRefresh(value => !value)} disabled={!canRead || scopeInvalidated || readOnlyPreview}>
            {autoRefresh ? "暂停自动刷新" : "恢复自动刷新"}
          </Button>
        </Space>
      </div>

      {!canRead && <Alert type="warning" showIcon message="无权读取运行展示屏" description="页面需要 platform:runtime-observer:access 与 platform:runtime-observer:read。"/>}
      {readOnlyPreview && <Alert type="info" showIcon message="只读角色预览" description="预览期间不读取受权限保护的运行数据；公开认证策略不包含个人身份信息。"/>}

      <Card title="运行概览" extra={<Typography.Text type="secondary">来源：宿主监控摘要</Typography.Text>}>
        {canRead && !readOnlyPreview && <QueryNotice query={monitoring} allowed={monitoringAllowed} title="运行概览"/>}
        <MetricGrid snapshot={monitoringAllowed ? monitoring.data : undefined} />
      </Card>

      <Card title="Outbox 队列采样" extra={<Typography.Text type="secondary">当前页面内存 · 最多 30 点</Typography.Text>}>
        <SampleChart samples={monitoringAllowed ? samples : []} />
      </Card>

      <Card title="事件类型覆盖与处理队列">
        {canRead && !readOnlyPreview && <QueryNotice query={monitoring} allowed={monitoringAllowed} title="事件拓扑"/>}
        <EventFlow snapshot={monitoringAllowed ? monitoring.data : undefined} />
      </Card>

      <Card title="模块与插件依赖">
        <ModulesPanel
          modules={modulesAllowed ? modules.data : undefined}
          plugins={modulesAllowed ? plugins.data : undefined}
          allowed={modulesAllowed}
          moduleQuery={modules}
          pluginQuery={plugins}
          onSelect={setSelection}
        />
      </Card>

      <Card title="最近事件元数据">
        {canRead && !readOnlyPreview && outbox.isError && eventsAllowed && (
          <Alert type="info" showIcon message="事件队列接口不可用" description={errorDescription(outbox.error)} />
        )}
        <EventTable
          monitoring={monitoringAllowed ? monitoring.data : undefined}
          outbox={eventsAllowed ? outbox.data : undefined}
          canReadMonitoring={monitoringAllowed}
          canReadEvents={eventsAllowed}
        />
        {eventsAllowed && outbox.dataUpdatedAt > 0 && (
          <Typography.Text type="secondary">事件队列最近成功读取：{timeLabel(outbox.dataUpdatedAt)}</Typography.Text>
        )}
      </Card>

      <Card title="认证配置流程">
        <Space direction="vertical" size={12} style={{ display: "flex" }}>
          <QueryNotice query={authMethods} allowed={publicPolicyAllowed} title="公开认证方式"/>
          {authMethods.isError && <Alert type="warning" showIcon message="认证方式配置不可用" description={errorDescription(authMethods.error)}/>}
          {registration.isError && <Alert type="warning" showIcon message="注册配置不可用" description={errorDescription(registration.error)}/>}
          <AuthFlow methods={publicPolicyAllowed ? authMethods.data : undefined} registration={publicPolicyAllowed ? registration.data : undefined} />
          <Typography.Text type="secondary">认证配置最近成功读取：{timeLabel(authMethods.dataUpdatedAt)}</Typography.Text>
        </Space>
      </Card>

      <ModuleDetails selection={modulesAllowed ? selection : null} records={moduleRecords} onClose={() => setSelection(null)} />
    </div>
  );
}
