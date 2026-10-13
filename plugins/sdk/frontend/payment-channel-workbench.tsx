"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { Alert, App, Button, Card, Input, InputNumber, Modal, QRCode, Space, Spin, Tag, Typography } from "antd";
import { api, mutate } from "@/platform/api/client";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { FormDrawer } from "@/platform/components/form-drawer";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { ResourceTable, type PageResult, type ProColumns } from "@/platform/components/resource-table";
import { TableActions } from "@/platform/components/table-actions";
import { useCenteredModalConfirm } from "@/platform/components/use-centered-modal-confirm";
import { formatDateTime } from "@/platform/i18n/format";
import { useListRequest } from "@/platform/routing/use-list-request";
import { usePreferences } from "@/platform/providers";

export type PaymentLocale = "zh-CN" | "zh-TW" | "en" | "ja" | "ko" | "fr" | "es" | "de";

export type PaymentChannelMessages = Record<string, string> & {
  settingsTitle: string;
  ordersTitle: string;
  forbidden: string;
  loadFailed: string;
  requestFailed: string;
  ready: string;
  notReady: string;
  saveSettings: string;
  saving: string;
  saved: string;
  secretConfigured: string;
  secretNotConfigured: string;
  secretKeepHint: string;
  createOrder: string;
  amountFen: string;
  amountYuan: string;
  subject: string;
  merchantOrderNo: string;
  providerTradeNo: string;
  status: string;
  createdAt: string;
  paidAt: string;
  refundedAt: string;
  refundStatus: string;
  actions: string;
  query: string;
  close: string;
  refund: string;
  queryRefund: string;
  confirmCloseTitle: string;
  confirmCloseBody: string;
  confirmRefundTitle: string;
  confirmRefundBody: string;
  refundReason: string;
  refundReasonRequired: string;
  refundReasonTooLong?: string;
  cancel: string;
  confirm: string;
  qrCode: string;
  qrExpiresAt: string;
  qrInvalid: string;
  qrExpired?: string;
  orderUnknownMessage?: string;
  orderPendingMessage?: string;
  refundPendingMessage?: string;
  statePending: string;
  statePaid: string;
  stateClosed: string;
  stateFailed: string;
  stateUnknown: string;
  stateRefundPending: string;
  stateRefunded: string;
  refundPending: string;
  refundFailed: string;
};

export type PaymentSettingField = {
  key: string;
  labelKey: string;
  kind: "text" | "textarea" | "secret";
  required?: boolean;
  maxLength?: number;
  helpKey?: string;
};

export type PaymentChannelWorkbenchProps = {
  providerId?: string;
  section: "settings" | "orders";
  fields?: readonly PaymentSettingField[];
  translations: Record<PaymentLocale, PaymentChannelMessages>;
  canRead: boolean;
  canUpdateSettings: boolean;
  canCreateOrders: boolean;
  canManageOrders: boolean;
  canRefund: boolean;
  readOnlyPreview?: boolean;
  subjectMaxLength?: number;
};

type ProviderSettings = {
  providerId: string;
  configured: boolean;
  version: number;
  values: Record<string, string>;
  secrets: Record<string, boolean>;
};

type PaymentOrder = {
  orderId: string;
  merchantOrderNo: string;
  providerId: string;
  amountFen: number;
  currency: string;
  subject: string;
  status: string;
  codeUrl: string | null;
  expiresAt: string | null;
  paidAt: string | null;
  createdAt: string | null;
  refundedAt: string | null;
  refundStatus: string | null;
  providerTradeNo: string | null;
};

type WorkbenchOrder = PaymentOrder & { id: string };

type OrderPage = {
  items: PaymentOrder[];
  total: number;
  page: number;
  size?: number;
  pageSize?: number;
};

type CreateOrderResult = PaymentOrder;

const PAYMENT_API = "/plugins/payment-core";

export const paymentOrdersQueryKey = (providerId?: string) => [providerId ?? "payment-core", "orders"] as const;
export const paymentSettingsQueryKey = (providerId: string) => [providerId, "settings"] as const;

export async function invalidatePaymentOrders(client: QueryClient, providerId?: string) {
  if (!providerId) {
    await client.invalidateQueries({ predicate: query => query.queryKey[1] === "orders" });
    return;
  }
  await Promise.all([
    client.invalidateQueries({ queryKey: paymentOrdersQueryKey(providerId) }),
    client.invalidateQueries({ queryKey: paymentOrdersQueryKey() }),
  ]);
}

function localDateTime(value: string | null, locale: string) {
  return value ? formatDateTime(value, locale) : "—";
}

function money(amountFen: number, locale: string) {
  if (!Number.isSafeInteger(amountFen) || amountFen < 0) return "—";
  return new Intl.NumberFormat(locale, { style: "currency", currency: "CNY" }).format(amountFen / 100);
}

function trustedQrUrl(order: PaymentOrder) {
  if (!order.codeUrl || order.codeUrl.length > 512 || /[\u0000-\u0020]/.test(order.codeUrl)) return null;
  try {
    if (order.providerId === "wechat-pay") {
      const url = new URL(order.codeUrl);
      return url.protocol === "weixin:"
          && url.hostname === "wxpay"
          && !url.port
          && !url.username
          && !url.password
          && ["/bizpayurl", "/bizpayurl/up"].includes(url.pathname)
          && Boolean(url.search)
          && !url.hash
        ? order.codeUrl
        : null;
    }
    const url = new URL(order.codeUrl);
    if (order.providerId === "alipay-pay") {
      return url.protocol === "https:" && url.hostname === "qr.alipay.com" && !url.username && !url.password
        ? order.codeUrl
        : null;
    }
    return null;
  } catch {
    return null;
  }
}

function statusCopy(status: string, text: (key: string) => string) {
  switch (status) {
    case "PENDING": return text("statePending");
    case "PAID": return text("statePaid");
    case "CLOSED": return text("stateClosed");
    case "FAILED": return text("stateFailed");
    case "REFUND_PENDING": return text("stateRefundPending");
    case "REFUNDED": return text("stateRefunded");
    default: return text("stateUnknown");
  }
}

function refundStatusCopy(status: string | null, text: (key: string) => string) {
  switch (status) {
    case "SUCCESS": return text("stateRefunded");
    case "FAILED": return text("refundFailed");
    case "PENDING": return text("refundPending");
    case "UNKNOWN": return text("stateUnknown");
    default: return "—";
  }
}

function qrAvailable(order: PaymentOrder | null, now: number) {
  if (!order || order.status !== "PENDING" || !order.codeUrl || !order.expiresAt) return false;
  const expiresAt = Date.parse(order.expiresAt);
  return Number.isFinite(expiresAt) && expiresAt > now && Boolean(trustedQrUrl(order));
}

export function PaymentChannelWorkbench(props: PaymentChannelWorkbenchProps) {
  const {
    providerId,
    section,
    fields = [],
    translations,
    canRead,
    canUpdateSettings,
    canCreateOrders,
    canManageOrders,
    canRefund,
    readOnlyPreview = false,
    subjectMaxLength = 127,
  } = props;
  const { locale } = usePreferences();
  const lang = (locale in translations ? locale : "en") as PaymentLocale;
  const messages = translations[lang] ?? translations.en;
  const text = (key: string) => messages[key] ?? messages.requestFailed;
  const { message } = App.useApp();
  const client = useQueryClient();
  const confirm = useCenteredModalConfirm();
  const [request, setRequest] = useListRequest();
  const [settingsForm] = Form.useForm<Record<string, string>>();
  const [createForm] = Form.useForm<{ amountFen?: number; subject?: string }>();
  const [settingsPending, setSettingsPending] = useState(false);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [createPending, setCreatePending] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [qrOrder, setQrOrder] = useState<PaymentOrder | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [refundTarget, setRefundTarget] = useState<PaymentOrder | null>(null);
  const [refundReason, setRefundReason] = useState("");
  const [refundPending, setRefundPending] = useState(false);
  const [refundError, setRefundError] = useState<string | null>(null);
  const [secretInputValues, setSecretInputValues] = useState<Record<string, string>>({});
  const [qrNow, setQrNow] = useState(() => Date.now());
  const createAttempt = useRef<{ fingerprint: string; key: string } | null>(null);
  const refundAttempt = useRef<string | null>(null);
  const mayWrite = !readOnlyPreview;

  const cacheOwner = providerId ?? "payment-core";
  const apiBase = providerId ? `/plugins/${encodeURIComponent(providerId)}` : PAYMENT_API;

  const settingsQuery = useQuery({
    queryKey: providerId ? paymentSettingsQueryKey(providerId) : [cacheOwner, "settings"],
    enabled: section === "settings" && Boolean(providerId) && canRead,
    queryFn: ({ signal }) => api<ProviderSettings>(apiBase + "/settings", { signal }),
  });

  useEffect(() => {
    if (!settingsQuery.data) return;
    settingsForm.setFieldsValue(settingsQuery.data.values ?? {});
    setSecretInputValues({});
  }, [settingsForm, settingsQuery.data]);

  useEffect(() => {
    if (section !== "orders") return;
    const interval = window.setInterval(() => setQrNow(Date.now()), 1000);
    return () => window.clearInterval(interval);
  }, [section]);

  const ordersQuery = useQuery({
    queryKey: [...paymentOrdersQueryKey(cacheOwner), providerId ?? "all", request],
    enabled: section === "orders" && canRead,
    refetchInterval: qrOrder?.status === "PENDING" ? 3_000 : false,
    refetchIntervalInBackground: false,
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({ page: String(request.page), size: String(request.pageSize) });
      if (providerId) params.delete("providerId");
      const result = await api<OrderPage>(apiBase + "/orders?" + params.toString(), { signal });
      return {
        items: result.items.map(order => ({ ...order, id: order.orderId })),
        total: result.total,
        page: result.page,
        pageSize: result.pageSize ?? result.size ?? request.pageSize,
      } satisfies PageResult<WorkbenchOrder>;
    },
  });

  useEffect(() => {
    if (!qrOrder) return;
    const current = ordersQuery.data?.items.find(order => order.orderId === qrOrder.orderId);
    if (current) setQrOrder(current);
  }, [ordersQuery.data, qrOrder]);

  async function saveSettings(values: Record<string, string>) {
    const current = settingsQuery.data;
    if (!current || !providerId || !canUpdateSettings || !mayWrite) return;
    const missingSecret = fields.some(field =>
      field.kind === "secret"
      && field.required
      && !current.secrets[field.key]
      && !(secretInputValues[field.key] ?? "").trim());
    if (missingSecret) {
      setSettingsError(text("requestFailed"));
      return;
    }
    setSettingsPending(true);
    setSettingsError(null);
    try {
      const valueFields = fields.filter(field => field.kind !== "secret");
      const secretFields = fields.filter(field => field.kind === "secret");
      const saved = await mutate<ProviderSettings>(apiBase + "/settings", "PUT", {
        expectedVersion: current.version,
        values: Object.fromEntries(valueFields.map(field => [field.key, values[field.key] ?? ""])),
        secrets: Object.fromEntries(secretFields
          .map(field => [field.key, secretInputValues[field.key] ?? ""] as const)
          .filter(([, value]) => value.trim() !== "")),
      });
      setSecretInputValues({});
      await client.setQueryData(paymentSettingsQueryKey(providerId), saved);
      await client.invalidateQueries({ queryKey: paymentSettingsQueryKey(providerId) });
      void message.success(text("saved"));
    } catch {
      setSettingsError(text("requestFailed"));
    } finally {
      setSettingsPending(false);
    }
  }

  function openCreate() {
    createAttempt.current = null;
    createForm.resetFields();
    setCreateError(null);
    setCreateOpen(true);
  }

  function closeCreate() {
    if (createPending) return;
    setCreateOpen(false);
    createForm.resetFields();
    setCreateError(null);
  }

  async function createOrder(values: { amountFen?: number; subject?: string }) {
    if (!providerId || !canCreateOrders || !mayWrite) return;
    setCreatePending(true);
    setCreateError(null);
    try {
      const amountFen = values.amountFen;
      const subject = values.subject?.trim() ?? "";
      if (!Number.isSafeInteger(amountFen) || Number(amountFen) < 1 || !subject) {
        setCreateError(text("requestFailed"));
        return;
      }
      const fingerprint = JSON.stringify([providerId, amountFen, subject]);
      if (!createAttempt.current || createAttempt.current.fingerprint !== fingerprint) {
        createAttempt.current = { fingerprint, key: window.crypto.randomUUID() };
      }
      const result = await mutate<CreateOrderResult>(apiBase + "/orders", "POST", {
        idempotencyKey: createAttempt.current.key,
        providerId,
        amountFen,
        subject,
      });
      createAttempt.current = null;
      setCreateOpen(false);
      createForm.resetFields();
      await invalidatePaymentOrders(client, providerId);
      const qrUrl = trustedQrUrl(result);
      setQrNow(Date.now());
      setQrOrder(qrUrl && qrAvailable(result, Date.now()) ? result : null);
      if (result.status === "UNKNOWN" || result.status === "CREATING") {
        void message.warning(messages.orderUnknownMessage ?? text("stateUnknown"));
      } else if (result.status === "PENDING" && !qrUrl) {
        void message.warning(messages.orderPendingMessage ?? text("statePending"));
      }
      if (!qrUrl && result.codeUrl) void message.error(text("qrInvalid"));
    } catch {
      setCreateError(text("requestFailed"));
    } finally {
      setCreatePending(false);
    }
  }

  async function refreshOrders() {
    await invalidatePaymentOrders(client, providerId);
  }

  async function queryOrder(order: PaymentOrder) {
    if (!canManageOrders || !mayWrite) return;
    try {
      const result = await mutate<PaymentOrder>(apiBase + "/orders/" + encodeURIComponent(order.orderId) + "/query", "POST", {});
      await refreshOrders();
      if (result.status === "UNKNOWN" || result.status === "CREATING")
        void message.warning(messages.orderUnknownMessage ?? text("stateUnknown"));
      else if (result.status === "PENDING")
        void message.warning(messages.orderPendingMessage ?? text("statePending"));
      else void message.success(text("saved"));
    } catch {
      void message.error(text("requestFailed"));
    }
  }

  function requestClose(order: PaymentOrder) {
    if (!canManageOrders || !mayWrite) return;
    confirm({
      title: text("confirmCloseTitle"),
      content: text("confirmCloseBody"),
      okText: text("close"),
      cancelText: text("cancel"),
      onOk: async () => {
        try {
          const result = await mutate<PaymentOrder>(apiBase + "/orders/" + encodeURIComponent(order.orderId) + "/close", "POST", {});
          await refreshOrders();
          if (result.status === "UNKNOWN" || result.status === "PENDING" || result.status === "CREATING")
            void message.warning(messages.orderUnknownMessage ?? text("stateUnknown"));
          else void message.success(text("saved"));
        } catch {
          void message.error(text("requestFailed"));
        }
      },
    });
  }

  function openRefund(order: PaymentOrder) {
    if (!canRefund || !mayWrite) return;
    refundAttempt.current = window.crypto.randomUUID();
    setRefundTarget(order);
    setRefundReason("");
    setRefundError(null);
  }

  async function submitRefund() {
    const reason = refundReason.trim();
    if (!refundTarget || !refundAttempt.current || !canRefund || !mayWrite || !reason) {
      setRefundError(text("refundReasonRequired"));
      return;
    }
    if (new TextEncoder().encode(reason).length > 80) {
      setRefundError(messages.refundReasonTooLong ?? text("requestFailed"));
      return;
    }
    setRefundPending(true);
    setRefundError(null);
    try {
      const result = await mutate<PaymentOrder>(apiBase + "/orders/" + encodeURIComponent(refundTarget.orderId) + "/refund", "POST", {
        idempotencyKey: refundAttempt.current,
        reason,
      });
      setRefundTarget(null);
      refundAttempt.current = null;
      await refreshOrders();
      if (result.status === "REFUND_PENDING" || result.refundStatus === "UNKNOWN" || result.refundStatus === "PENDING")
        void message.warning(messages.refundPendingMessage ?? text("refundPending"));
      else void message.success(text("saved"));
    } catch {
      setRefundError(text("requestFailed"));
    } finally {
      setRefundPending(false);
    }
  }

  async function queryRefund(order: PaymentOrder) {
    if (!canRefund || !mayWrite) return;
    try {
      const result = await mutate<PaymentOrder>(apiBase + "/orders/" + encodeURIComponent(order.orderId) + "/refund/query", "POST", {});
      await refreshOrders();
      if (result.status !== "REFUNDED") void message.warning(messages.refundPendingMessage ?? text("refundPending"));
      else void message.success(text("saved"));
    } catch {
      void message.error(text("requestFailed"));
    }
  }

  const orderColumns: ProColumns<WorkbenchOrder>[] = [
    { title: text("merchantOrderNo"), dataIndex: "merchantOrderNo" },
    { title: text("providerTradeNo"), dataIndex: "providerTradeNo" },
    { title: text("amountYuan"), dataIndex: "amountFen", render: (_, row) => money(row.amountFen, locale) },
    { title: text("subject"), dataIndex: "subject" },
    { title: text("status"), dataIndex: "status", render: (_, row) => <Tag>{statusCopy(row.status, text)}</Tag> },
    { title: text("createdAt"), dataIndex: "createdAt", render: (_, row) => localDateTime(row.createdAt, locale) },
    { title: text("paidAt"), dataIndex: "paidAt", render: (_, row) => localDateTime(row.paidAt, locale) },
    { title: text("refundedAt"), dataIndex: "refundedAt", render: (_, row) => localDateTime(row.refundedAt, locale) },
    { title: text("refundStatus"), dataIndex: "refundStatus", render: (_, row) => refundStatusCopy(row.refundStatus, text) },
    {
      title: text("actions"),
      key: "actions",
      render: (_, row) => <TableActions>
        {qrAvailable(row, qrNow) && (
          <Button type="link" onClick={() => {
            if (!trustedQrUrl(row)) {
              void message.error(text("qrInvalid"));
              return;
            }
            setQrOrder(row);
          }}>{text("qrCode")}</Button>
        )}
        {canManageOrders && mayWrite && <Button type="link" onClick={() => void queryOrder(row)}>{text("query")}</Button>}
        {canManageOrders && mayWrite && ["CREATING", "UNKNOWN", "PENDING"].includes(row.status) && <Button type="link" danger onClick={() => requestClose(row)}>{text("close")}</Button>}
        {canRefund && mayWrite && row.status === "PAID" && <Button type="link" danger onClick={() => openRefund(row)}>{text("refund")}</Button>}
        {canRefund && mayWrite && (row.status === "REFUND_PENDING" || row.refundStatus === "PENDING") && (
          <Button type="link" onClick={() => void queryRefund(row)}>{text("queryRefund")}</Button>
        )}
      </TableActions>,
    },
  ];

  if (section === "settings") {
    if (!providerId) return <Alert type="error" showIcon message={text("requestFailed")} />;
    if (!canRead) return <Alert type="warning" showIcon message={text("forbidden")} />;
    const secretConfigured = settingsQuery.data?.secrets ?? {};
    return (
      <Space direction="vertical" size="large" style={{ width: "100%" }}>
        <Card title={text("settingsTitle")} extra={settingsQuery.data && (
          <Tag color={settingsQuery.data.configured ? "success" : "warning"}>
            {settingsQuery.data.configured ? text("ready") : text("notReady")}
          </Tag>
        )}>
          <FeedbackBubble id="payment-settings-error" content={settingsError} />
          {settingsQuery.isLoading ? <Spin /> : null}
          {settingsQuery.error ? <Alert type="error" showIcon message={text("loadFailed")} action={<Button onClick={() => void settingsQuery.refetch()}>{text("query")}</Button>} /> : null}
          {!settingsQuery.error && settingsQuery.data && (
            <Form
              form={settingsForm}
              layout="vertical"
              disabled={settingsPending || !canUpdateSettings || !mayWrite}
              onFinish={values => saveSettings(values)}
            >
              {fields.map(field => {
                const isSecret = field.kind === "secret";
                const configured = isSecret && Boolean(secretConfigured[field.key]);
                const required = Boolean(field.required) && (!isSecret || !configured);
                return (
                  <Form.Item
                    key={field.key}
                    name={isSecret ? undefined : field.key}
                    label={text(field.labelKey)}
                    help={field.helpKey ? text(field.helpKey) : undefined}
                    rules={required ? [{ required: true, whitespace: true, message: text("requestFailed") }] : []}
                  >
                    {isSecret ? (
                      <Input.Password
                        value={secretInputValues[field.key] ?? ""}
                        visibilityToggle={false}
                        autoComplete="new-password"
                        maxLength={field.maxLength}
                        placeholder={configured ? text("secretKeepHint") : text("secretNotConfigured")}
                        onChange={event => setSecretInputValues(current => ({ ...current, [field.key]: event.target.value }))}
                      />
                    ) : field.kind === "textarea" ? (
                      <Input.TextArea maxLength={field.maxLength} autoSize={{ minRows: 4, maxRows: 10 }} />
                    ) : (
                      <Input maxLength={field.maxLength} autoComplete="off" />
                    )}
                  </Form.Item>
                );
              })}
              {fields.some(field => field.kind === "secret") && (
                <Typography.Paragraph type="secondary">
                  {fields.filter(field => field.kind === "secret").map(field =>
                    text(field.labelKey) + ": " + (secretConfigured[field.key] ? text("secretConfigured") : text("secretNotConfigured")),
                  ).join(" · ")}
                </Typography.Paragraph>
              )}
              {canUpdateSettings && mayWrite && <Button type="primary" htmlType="submit" loading={settingsPending}>{text("saveSettings")}</Button>}
            </Form>
          )}
        </Card>
      </Space>
    );
  }

  if (!canRead) return <Alert type="warning" showIcon message={text("forbidden")} />;
  return (
    <>
      {ordersQuery.error && <FeedbackBubble id="payment-orders-error" content={text("loadFailed")} />}
      <Card title={text("ordersTitle")}>
      <ResourceTable<WorkbenchOrder>
        result={ordersQuery.data}
        loading={ordersQuery.isFetching}
        error={ordersQuery.error}
        request={request}
        onChange={setRequest}
        onRetry={() => void ordersQuery.refetch()}
        searchable={false}
        actions={canCreateOrders && mayWrite && providerId ? (
          <Button type="primary" onClick={openCreate}>{text("createOrder")}</Button>
        ) : undefined}
        columns={orderColumns}
      />
      </Card>

      <FormDrawer
        open={createOpen}
        title={text("createOrder")}
        pending={createPending}
        formId="payment-create-order"
        onClose={closeCreate}
      >
        <FeedbackBubble id="payment-create-order-error" content={createError} />
        <Form
          id="payment-create-order"
          form={createForm}
          layout="vertical"
          disabled={createPending}
          onFinish={values => createOrder(values)}
        >
          <Form.Item
            name="amountFen"
            label={text("amountFen")}
            rules={[
              { required: true, message: text("requestFailed") },
              { type: "number", min: 1, max: 2_147_483_647, message: text("requestFailed") },
            ]}
          >
            <InputNumber min={1} max={2_147_483_647} precision={0} step={1} style={{ width: "100%" }} />
          </Form.Item>
          <Form.Item
            name="subject"
            label={text("subject")}
            rules={[{ required: true, whitespace: true, max: subjectMaxLength, message: text("requestFailed") }]}
          >
            <Input maxLength={subjectMaxLength} />
          </Form.Item>
        </Form>
      </FormDrawer>

      <Modal
        open={refundTarget !== null}
        title={text("confirmRefundTitle")}
        okText={text("refund")}
        cancelText={text("cancel")}
        confirmLoading={refundPending}
        okButtonProps={{ danger: true, disabled: refundPending || !refundReason.trim() }}
        cancelButtonProps={{ disabled: refundPending }}
        onCancel={() => {
          if (refundPending) return;
          setRefundTarget(null);
          refundAttempt.current = null;
          setRefundError(null);
        }}
        onOk={() => void submitRefund()}
      >
        <Typography.Paragraph>
          {text("confirmRefundBody")} {refundTarget ? money(refundTarget.amountFen, locale) : ""}
        </Typography.Paragraph>
        <Input.TextArea
          maxLength={80}
          value={refundReason}
          disabled={refundPending}
          placeholder={text("refundReason")}
          onChange={event => setRefundReason(event.target.value)}
        />
        <FeedbackBubble id="payment-refund-error" content={refundError} />
      </Modal>

      <Modal
        open={qrOrder !== null}
        title={text("qrCode")}
        footer={null}
        onCancel={() => setQrOrder(null)}
      >
        {qrOrder?.status === "PAID" ? (
          <Alert type="success" showIcon message={text("statePaid")} />
        ) : qrOrder && qrAvailable(qrOrder, qrNow) ? (
          <Space direction="vertical" align="center" style={{ width: "100%" }}>
            <Typography.Text>{qrOrder.merchantOrderNo}</Typography.Text>
            <QRCode value={trustedQrUrl(qrOrder)!} size={240} />
            <Typography.Text type="secondary">
              {text("qrExpiresAt")}: {localDateTime(qrOrder.expiresAt, locale)}
            </Typography.Text>
          </Space>
        ) : <Alert
          type="warning"
          showIcon
          message={qrOrder && qrOrder.status !== "PENDING"
            ? statusCopy(qrOrder.status, text)
            : qrOrder && qrOrder.expiresAt && Date.parse(qrOrder.expiresAt) <= qrNow
              ? messages.qrExpired ?? text("qrInvalid")
              : text("qrInvalid")}
        />}
      </Modal>
    </>
  );
}
