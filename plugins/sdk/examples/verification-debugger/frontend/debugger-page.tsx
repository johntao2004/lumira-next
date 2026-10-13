"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, App, Button, Card, Col, Descriptions, Input, Modal, Row, Select, Space, Spin, Table, Tag, Typography } from "antd";
import { api, mutate } from "@/platform/api/client";
import { AsyncButton } from "@/platform/components/async-button";
import { useSessionScope } from "@/platform/auth/use-session-scope";
import { isProviderSendSuccessful } from "./provider-response";
import {
  DEBUG_RECIPIENT,
  type DebuggerMessage,
  type DebuggerMessagesResponse,
  type DebuggerSessionResponse,
  type DebuggerStatus,
  type InvokeResult,
  type MockProvider,
  type OwnedDebuggerSession,
} from "./types";

const API_ROOT = "/plugins/verification-debugger";
const MESSAGE_QUERY_ROOT = ["verification-debugger", "messages"] as const;

type InvokeLog = {
  sessionId: string;
  provider: MockProvider;
  endpoint: string;
  status: number;
  request: string;
  response: string;
};

function isAbortError(error: unknown) {
  return error instanceof Error && error.name === "AbortError";
}

function describeError(error: unknown) {
  return error instanceof Error && error.message ? error.message : "请求失败，请检查服务状态和权限。";
}

function isLoopbackEndpoint(value: string) {
  try {
    const endpoint = new URL(value);
    const hostname = endpoint.hostname.toLowerCase().replace(/^\[|\]$/g, "");
    return (endpoint.protocol === "http:" || endpoint.protocol === "https:")
      && !endpoint.username
      && !endpoint.password
      && (hostname === "localhost" || hostname === "::1" || /^127(?:\.\d{1,3}){3}$/.test(hostname));
  } catch {
    return false;
  }
}

function sessionConfigurationJson(session: OwnedDebuggerSession) {
  const values: Record<string, unknown> = {
    ...session.configuration,
    endpoint: session.endpoint,
    bearerToken: session.bearerToken,
    recipient: DEBUG_RECIPIENT,
    providers: session.providers,
  };
  const entries = Object.entries(values).filter(([, value]) => value !== undefined && value !== null && value !== "");
  return JSON.stringify(Object.fromEntries(entries), null, 2);
}

function providerRequestBody(provider: MockProvider, code: string) {
  if (provider === "aliyun") {
    return {
      PhoneNumbers: DEBUG_RECIPIENT,
      SignName: "LumiraDebug",
      TemplateCode: "SMS_DEBUG",
      TemplateParam: JSON.stringify({ code }),
    };
  }
  return {
    PhoneNumberSet: [DEBUG_RECIPIENT],
    SmsSdkAppId: "1400000000",
    TemplateId: "1234",
    SignName: "LumiraDebug",
    TemplateParamSet: [code],
  };
}

function providerSettings(session: OwnedDebuggerSession | null, provider: MockProvider) {
  const value = session?.providers?.[provider];
  if (!value?.endpoint || !isLoopbackEndpoint(value.endpoint)) return null;
  const accessKeyId = provider === "aliyun" ? value.accessKeyId : value.secretId;
  const accessKeySecret = provider === "aliyun" ? value.accessKeySecret : value.secretKey;
  if (!accessKeyId || !accessKeySecret) return null;
  return { endpoint: value.endpoint, accessKeyId, accessKeySecret };
}

function shellQuote(value: string) {
  return `'${value.replace(/'/g, "'\\''")}'`;
}

function curlSample(endpoint: string, accessKeyId: string, accessKeySecret: string, body: unknown) {
  return [
    `curl --request POST ${shellQuote(endpoint)} \\`,
    "  --header 'Content-Type: application/json' \\",
    `  --header ${shellQuote(`X-Lumira-Debug-Access-Key: ${accessKeyId}`)} \\`,
    `  --header ${shellQuote(`Authorization: Bearer ${accessKeySecret}`)} \\`,
    `  --data ${shellQuote(JSON.stringify(body))}`,
  ].join("\n");
}

function formatJson(value: unknown) {
  const json = JSON.stringify(value ?? null, null, 2) ?? "null";
  return json.replace(/\b\d{6}\b/g, "••••••");
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function maskMessage(row: DebuggerMessage) {
  const text = row.message || "—";
  if (row.code) return text.split(row.code).join("••••••");
  return text.replace(/\b\d{6}\b/g, "••••••");
}

export function VerificationDebuggerPage({
  canRead,
  canControl,
  canFixture,
  readOnlyPreview,
}: {
  canRead: boolean;
  canControl: boolean;
  canFixture: boolean;
  readOnlyPreview: boolean;
}) {
  const { message } = App.useApp();
  const client = useQueryClient();
  const operationSignal = useSessionScope();
  const [scopeInvalidated, setScopeInvalidated] = useState(false);
  const [ownedSession, setOwnedSession] = useState<OwnedDebuggerSession | null>(null);
  const [expiredNotice, setExpiredNotice] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [ending, setEnding] = useState(false);
  const [invokePending, setInvokePending] = useState(false);
  const [provider, setProvider] = useState<MockProvider>("aliyun");
  const [verificationCode, setVerificationCode] = useState("123456");
  const [invokeLog, setInvokeLog] = useState<InvokeLog | null>(null);
  const [activeCode, setActiveCode] = useState<DebuggerMessage | null>(null);
  const [codeQueue, setCodeQueue] = useState<DebuggerMessage[]>([]);
  const codeQueueRef = useRef<DebuggerMessage[]>([]);
  const currentWindowId = useRef<string | null>(null);
  const currentWindowGeneration = useRef(0);
  const seenMessageIds = useRef(new Set<string>());
  const initializedInboxFor = useRef<string | null>(null);
  const previousUserId = useRef<{ initialized: boolean; value?: string }>({ initialized: false });

  const invalidateCurrentWindow = useCallback(() => {
    currentWindowGeneration.current += 1;
    currentWindowId.current = null;
    return currentWindowGeneration.current;
  }, []);

  const updateCodeQueue = useCallback((updater: DebuggerMessage[] | ((current: DebuggerMessage[]) => DebuggerMessage[])) => {
    const next = typeof updater === "function" ? updater(codeQueueRef.current) : updater;
    codeQueueRef.current = next;
    setCodeQueue(next);
  }, []);

  const identity = useQuery({
    queryKey: ["session"],
    queryFn: ({ signal }) => api<{ userId: string }>("/auth/me", { signal }),
    enabled: canControl && canRead,
    staleTime: 30_000,
  });
  const userId = identity.data?.userId;
  const sessionIsCurrent = Boolean(ownedSession && ownedSession.ownerUserId === userId);
  const expiresAt = ownedSession ? Date.parse(ownedSession.expiresAt) : Number.NaN;
  const sessionIsUnexpired = Number.isFinite(expiresAt) && expiresAt > Date.now();
  const activeSession = canRead && canControl && !readOnlyPreview && !scopeInvalidated && sessionIsCurrent && sessionIsUnexpired
    ? ownedSession
    : null;

  const status = useQuery({
    queryKey: ["verification-debugger", "status"],
    queryFn: ({ signal }) => api<DebuggerStatus>(`${API_ROOT}/status`, { signal }),
    enabled: canRead && !scopeInvalidated,
    staleTime: 5_000,
    refetchOnWindowFocus: true,
  });

  const clearPrivateState = useCallback((expired = false) => {
    invalidateCurrentWindow();
    setOwnedSession(null);
    setActiveCode(null);
    updateCodeQueue([]);
    setInvokeLog(null);
    setStarting(false);
    setInvokePending(false);
    setVerificationCode("");
    setPageError(null);
    setExpiredNotice(expired);
    seenMessageIds.current.clear();
    initializedInboxFor.current = null;
    void client.cancelQueries({ queryKey: MESSAGE_QUERY_ROOT });
    client.removeQueries({ queryKey: MESSAGE_QUERY_ROOT });
  }, [client, invalidateCurrentWindow, updateCodeQueue]);

  useEffect(() => {
    let disposed = false;
    const scopeSignal = operationSignal();
    const onScopeInvalidated = () => {
      queueMicrotask(() => {
        if (disposed) return;
        setScopeInvalidated(true);
        clearPrivateState(false);
      });
    };
    if (scopeSignal.aborted) {
      onScopeInvalidated();
    } else {
      scopeSignal.addEventListener("abort", onScopeInvalidated, { once: true });
    }
    return () => {
      disposed = true;
      scopeSignal.removeEventListener("abort", onScopeInvalidated);
    };
  }, [clearPrivateState, operationSignal]);

  useEffect(() => () => {
    invalidateCurrentWindow();
    seenMessageIds.current.clear();
    initializedInboxFor.current = null;
    codeQueueRef.current = [];
    void client.cancelQueries({ queryKey: MESSAGE_QUERY_ROOT });
    client.removeQueries({ queryKey: MESSAGE_QUERY_ROOT });
  }, [client, invalidateCurrentWindow]);

  useEffect(() => {
    const previous = previousUserId.current;
    if (previous.initialized && previous.value !== userId) clearPrivateState(false);
    previousUserId.current = { initialized: true, value: userId };
  }, [clearPrivateState, userId]);

  useEffect(() => {
    if (!canRead || !canControl || readOnlyPreview) clearPrivateState(false);
  }, [canControl, canRead, clearPrivateState, readOnlyPreview]);

  const messages = useQuery({
    queryKey: [...MESSAGE_QUERY_ROOT, userId, activeSession?.id],
    queryFn: ({ signal }) => api<DebuggerMessagesResponse>(`${API_ROOT}/messages`, { signal }),
    enabled: Boolean(canRead && canControl && activeSession && userId && !scopeInvalidated),
    refetchInterval: activeSession ? 1_000 : false,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  });
  const safeMessages = useMemo(
    () => (messages.data?.items ?? []).filter(item => item.recipient === DEBUG_RECIPIENT),
    [messages.data],
  );

  useEffect(() => {
    const sessionId = activeSession?.id;
    if (!sessionId) {
      initializedInboxFor.current = null;
      return;
    }
    if (!messages.data) return;
    if (initializedInboxFor.current !== sessionId) {
      initializedInboxFor.current = sessionId;
      for (const item of safeMessages) seenMessageIds.current.add(item.id);
      return;
    }
    const arrived: DebuggerMessage[] = [];
    for (const item of safeMessages) {
      if (seenMessageIds.current.has(item.id)) continue;
      seenMessageIds.current.add(item.id);
      arrived.push(item);
    }
    if (arrived.length) updateCodeQueue(current => [...current, ...arrived]);
  }, [activeSession?.id, messages.data, safeMessages, updateCodeQueue]);

  useEffect(() => {
    if (!activeCode && codeQueue.length) {
      setActiveCode(codeQueue[0]);
      updateCodeQueue(current => current.slice(1));
    }
  }, [activeCode, codeQueue, updateCodeQueue]);

  useEffect(() => {
    if (!activeSession) return;
    const delay = Number.isFinite(expiresAt) ? Math.max(0, expiresAt - Date.now()) : 0;
    const timer = window.setTimeout(() => {
      clearPrivateState(true);
      void message.info("本地接收窗口已过期，缓存的验证码和临时配置已清除。");
    }, delay);
    return () => window.clearTimeout(timer);
  }, [activeSession, clearPrivateState, expiresAt, message]);

  const statusValue = status.data;
  const statusEnabled = statusValue?.enabled === true;
  const receiverReady = statusValue?.receiverReady === true;
  const safeRecipientAllowed = statusValue?.allowedRecipient === DEBUG_RECIPIENT;
  const canStartSession = Boolean(
    canRead && canControl && !readOnlyPreview && !scopeInvalidated && userId && statusEnabled && receiverReady && safeRecipientAllowed && !activeSession,
  );

  async function startSession() {
    if (!canStartSession || !userId) return;
    const generation = invalidateCurrentWindow();
    const signal = operationSignal();
    setStarting(true);
    setInvokePending(false);
    setPageError(null);
    setExpiredNotice(false);
    try {
      const result = await mutate<DebuggerSessionResponse>(`${API_ROOT}/sessions`, "POST", {}, signal);
      if (generation !== currentWindowGeneration.current || signal.aborted) return;
      if (result.recipient !== DEBUG_RECIPIENT || !result.endpoint || !isLoopbackEndpoint(result.endpoint) || !result.bearerToken) {
        try {
          await mutate<void>(`${API_ROOT}/sessions`, "DELETE", undefined, signal);
        } catch {
          // The invalid session remains server-owned and expires with its short TTL.
        }
        throw new Error("服务端返回的会话不符合固定测试号码或本地回环地址要求，已拒绝显示配置。");
      }
      seenMessageIds.current.clear();
      initializedInboxFor.current = result.id;
      setActiveCode(null);
      updateCodeQueue([]);
      setInvokeLog(null);
      currentWindowId.current = result.id;
      client.removeQueries({ queryKey: MESSAGE_QUERY_ROOT });
      setOwnedSession({ ...result, ownerUserId: userId });
      setVerificationCode(current => /^\d{6}$/.test(current) ? current : "123456");
      void message.success("短信调试会话已开启，可以调用本地模拟接口。");
    } catch (error) {
      if (generation === currentWindowGeneration.current && !signal.aborted && !isAbortError(error)) {
        setPageError(describeError(error));
      }
    } finally {
      if (generation === currentWindowGeneration.current) setStarting(false);
    }
  }

  async function endSession() {
    if (!activeSession) return;
    setEnding(true);
    clearPrivateState(false);
    try {
      await mutate<void>(`${API_ROOT}/sessions`, "DELETE", undefined, operationSignal());
      void message.success("本地接收窗口已结束，临时配置和验证码已清除。");
    } catch (error) {
      if (!isAbortError(error)) setPageError(describeError(error));
    } finally {
      setEnding(false);
    }
  }

  async function invokeProvider() {
    const settings = providerSettings(activeSession, provider);
    if (!canFixture || readOnlyPreview || !activeSession || !settings || !statusEnabled || scopeInvalidated || !/^\d{6}$/.test(verificationCode)) return;
    const windowId = activeSession.id;
    const generation = currentWindowGeneration.current;
    const signal = operationSignal();
    const isCurrentWindow = () => currentWindowId.current === windowId
      && currentWindowGeneration.current === generation
      && !signal.aborted;
    if (!isCurrentWindow()) return;
    const body = providerRequestBody(provider, verificationCode);
    const curl = curlSample(settings.endpoint, settings.accessKeyId, settings.accessKeySecret, body);
    const request = JSON.stringify({
      method: "POST",
      endpoint: settings.endpoint,
      headers: {
        "Content-Type": "application/json",
        "X-Lumira-Debug-Access-Key": settings.accessKeyId,
        Authorization: "Bearer [本地假凭据]",
      },
      body,
      curl,
    }, null, 2);
    setInvokePending(true);
    setPageError(null);
    try {
      const result = await mutate<InvokeResult>(`${API_ROOT}/invoke`, "POST", { provider, body }, signal, 8_000);
      if (!isCurrentWindow()) return;
      setInvokeLog({
        sessionId: windowId,
        provider,
        endpoint: settings.endpoint,
        status: result.status,
        request,
        response: formatJson({ status: result.status, body: result.body }),
      });
      if (isProviderSendSuccessful(provider, result.status, result.body)) {
        void message.success("本地模拟接口调用成功；当前会话收件箱将收到你输入的合成验证码。");
      } else {
        void message.warning("本地模拟接口已响应，但 HTTP 或供应商协议结果未确认成功；请检查响应详情，收件箱未确认收到验证码。");
      }
    } catch (error) {
      if (!isAbortError(error) && isCurrentWindow()) setPageError(describeError(error));
    } finally {
      if (isCurrentWindow()) setInvokePending(false);
    }
  }

  async function copyText(value: string, label: string) {
    try {
      if (!navigator.clipboard?.writeText) throw new Error("当前浏览器不支持剪贴板复制。");
      await navigator.clipboard.writeText(value);
      void message.success(`${label}已复制。`);
    } catch (error) {
      void message.error(describeError(error));
    }
  }

  function closeCodeModal() {
    setActiveCode(null);
  }

  const configText = activeSession ? sessionConfigurationJson(activeSession) : "";
  const sessionTimeText = activeSession ? `${formatDate(new Date().toISOString())} · 到期 ${formatDate(activeSession.expiresAt)}` : "";
  const selectedProviderSettings = providerSettings(activeSession, provider);
  const selectedRequestBody = providerRequestBody(provider, verificationCode);
  const selectedCurl = selectedProviderSettings && /^\d{6}$/.test(verificationCode)
    ? curlSample(selectedProviderSettings.endpoint, selectedProviderSettings.accessKeyId, selectedProviderSettings.accessKeySecret, selectedRequestBody)
    : "";

  if (!canRead) {
    return <Alert showIcon type="warning" message="当前账号没有读取验证调试器的权限。" />;
  }

  return (
    <div className="verification-debugger-page">
      <div style={{ marginBottom: 16 }}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          开启短信调试后生成本地地址和 fake keys，再调用阿里云或腾讯云协议的本地模拟接口，检查验证码请求与接收流程。
        </Typography.Paragraph>
      </div>

      {readOnlyPreview && <Alert showIcon type="info" style={{ marginBottom: 16 }} message="角色预览为只读模式。" />}
      {scopeInvalidated && <Alert showIcon type="warning" style={{ marginBottom: 16 }} message="登录身份已变化，私有会话与验证码已清除；请重新载入此页面后继续。" />}
      {pageError && <Alert showIcon closable type="error" style={{ marginBottom: 16 }} message={pageError} onClose={() => setPageError(null)} />}
      {expiredNotice && <Alert showIcon type="info" style={{ marginBottom: 16 }} message="本地接收窗口已过期，临时配置和验证码已清除。" />}

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card title="本地接收窗口" extra={activeSession ? <Tag color="green">已开启</Tag> : <Tag>未开启</Tag>}>
            <Typography.Paragraph type="secondary">
              会话只属于当前账号，最长保留 10 分钟。接收号码固定为测试号码 {DEBUG_RECIPIENT}；页面离开或登录身份变化时会清理当前验证码。
            </Typography.Paragraph>
            <Space wrap>
              <AsyncButton
                type="primary"
                label="开始短信调试"
                pending={starting}
                disabled={!canStartSession || status.isFetching}
                onClick={() => void startSession()}
              />
              <AsyncButton
                danger
                label="结束短信调试"
                pending={ending}
                disabled={!activeSession}
                onClick={() => void endSession()}
              />
            </Space>
            {status.isLoading && <div style={{ marginTop: 16 }}><Spin size="small" /> <Typography.Text type="secondary">正在检查本地服务…</Typography.Text></div>}
            {status.isError && <Typography.Paragraph type="danger" style={{ marginTop: 16, marginBottom: 0 }}>无法读取本地调试服务状态，请稍后重试。</Typography.Paragraph>}
            {statusValue && (
              <Descriptions size="small" column={1} style={{ marginTop: 16 }}>
                <Descriptions.Item label="调试开关">
                  <Tag color={statusEnabled ? "green" : "default"}>{statusEnabled ? "已启用" : "未启用"}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="本地接收服务">
                  <Tag color={receiverReady ? "green" : "orange"}>{receiverReady ? "已就绪" : "未就绪"}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="测试号码">
                  <Typography.Text>{DEBUG_RECIPIENT}</Typography.Text>
                  {statusValue.allowedRecipient !== DEBUG_RECIPIENT && <Typography.Text type="danger"> · 服务端测试号码不匹配</Typography.Text>}
                </Descriptions.Item>
                {activeSession && <Descriptions.Item label="会话时间">{sessionTimeText}</Descriptions.Item>}
              </Descriptions>
            )}
            {statusValue && !statusEnabled && <Alert showIcon type="warning" style={{ marginTop: 16 }} message="本地调试开关未启用，无法开启会话或生成协议样例。" />}
            {statusValue && statusEnabled && !receiverReady && <Alert showIcon type="info" style={{ marginTop: 16 }} message="本地接收服务尚未就绪；准备完成后才能开启会话。" />}
            {!canControl && !readOnlyPreview && <Typography.Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 0 }}>当前账号可以查看状态，但没有控制会话的权限。</Typography.Paragraph>}
            {activeSession && (
              <div style={{ marginTop: 18 }}>
                <Alert
                  showIcon
                  type="info"
                  message="本地模拟配置"
                  description="这是当前账号的短期开发 token，仅用于本地回环接收；结束会话、过期或身份变化后立即失效。它不是云厂商密钥。"
                />
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12, marginTop: 12 }}>
                  <Typography.Text strong>只读配置</Typography.Text>
                  <Button size="small" disabled={!configText} onClick={() => void copyText(configText, "本地模拟配置")}>复制配置</Button>
                </div>
                {configText && (
                  <pre style={{ marginTop: 8, marginBottom: 0, padding: 12, borderRadius: 8, background: "rgba(127, 127, 127, 0.08)", whiteSpace: "pre-wrap", overflowWrap: "anywhere", wordBreak: "break-word", maxHeight: 260, overflow: "auto" }}>
                    {configText}
                  </pre>
                )}
              </div>
            )}
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title="本地短信模拟接口" extra={<Tag color="blue">阿里云 / 腾讯云协议</Tag>}>
            <Typography.Paragraph type="secondary">
              会话开启后，页面会为当前账号生成 loopback 接口地址和短期 fake keys。点“调用本地模拟接口”会通过平台同源后端桥接请求本地 adapter，并返回供应商形状的模拟响应。
            </Typography.Paragraph>
            <Row gutter={[12, 12]}>
              <Col xs={24} sm={12}>
                <Typography.Text strong>供应商协议</Typography.Text>
                <Select<MockProvider>
                  aria-label="供应商协议"
                  style={{ width: "100%", marginTop: 6 }}
                  value={provider}
                  onChange={setProvider}
                  disabled={!canFixture || readOnlyPreview || invokePending}
                  options={[
                    { value: "aliyun", label: "阿里云短信" },
                    { value: "tencent", label: "腾讯云短信" },
                  ]}
                />
              </Col>
              <Col xs={24} sm={12}>
                <Typography.Text strong>合成验证码</Typography.Text>
                <Input
                  aria-label="合成验证码"
                  inputMode="numeric"
                  autoComplete="off"
                  maxLength={6}
                  style={{ marginTop: 6 }}
                  value={verificationCode}
                  onChange={event => setVerificationCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
                  disabled={!canFixture || readOnlyPreview || invokePending}
                  placeholder="输入 6 位测试码"
                />
              </Col>
            </Row>

            {activeSession && selectedProviderSettings ? (
              <>
                <Descriptions size="small" column={1} style={{ marginTop: 16 }}>
                  <Descriptions.Item label="本地模拟地址">
                    <Typography.Text code style={{ overflowWrap: "anywhere" }}>{selectedProviderSettings.endpoint}</Typography.Text>
                  </Descriptions.Item>
                  <Descriptions.Item label={provider === "aliyun" ? "Fake Access Key ID" : "Fake Secret ID"}>
                    <Typography.Text code copyable>{selectedProviderSettings.accessKeyId}</Typography.Text>
                  </Descriptions.Item>
                  <Descriptions.Item label={provider === "aliyun" ? "Fake Access Key Secret" : "Fake Secret Key"}>
                    <Typography.Text code copyable>{selectedProviderSettings.accessKeySecret}</Typography.Text>
                  </Descriptions.Item>
                </Descriptions>
                <Alert
                  showIcon
                  type="info"
                  style={{ marginTop: 8 }}
                  message="这些是仅在本地 adapter 生效的短期 fake keys，不是阿里云或腾讯云凭据。"
                />
                <div style={{ marginTop: 16 }}>
                  <CodeSnippet title="可复制的本地 curl 请求" value={selectedCurl || "输入 6 位验证码后生成请求示例。"} onCopy={() => selectedCurl && void copyText(selectedCurl, "curl 请求")} />
                </div>
              </>
            ) : (
              <Alert
                showIcon
                type="info"
                style={{ marginTop: 16 }}
                message={activeSession ? "当前供应商的本地地址或 fake keys 尚未就绪。" : "先开始短信调试，生成当前会话的本地地址和 fake keys。"}
              />
            )}

            <Space wrap style={{ marginTop: 14 }}>
              <AsyncButton
                type="primary"
                label="调用本地模拟接口"
                pending={invokePending}
                disabled={!canFixture || readOnlyPreview || !activeSession || !selectedProviderSettings || !statusEnabled || scopeInvalidated || !/^\d{6}$/.test(verificationCode)}
                onClick={() => void invokeProvider()}
              />
              {!canFixture && !readOnlyPreview && <Typography.Text type="secondary">当前账号没有调用本地模拟接口的权限。</Typography.Text>}
            </Space>
            <Alert
              showIcon
              type="warning"
              style={{ marginTop: 16 }}
              message="仅调用本地模拟接口，不会向阿里云或腾讯云真实投递，也不会产生短信费用。"
            />
            {activeSession && invokeLog?.sessionId === activeSession.id && (
              <div style={{ marginTop: 18 }}>
                <Typography.Text type={invokeLog.status >= 200 && invokeLog.status < 300 ? "success" : "warning"} strong>
                  {invokeLog.provider === "aliyun" ? "阿里云协议" : "腾讯云协议"}响应 · HTTP {invokeLog.status}
                </Typography.Text>
                <Row gutter={[12, 12]} style={{ marginTop: 8 }}>
                  <Col xs={24}>
                    <CodeSnippet title="实际请求（经同源本地桥接）" value={invokeLog.request} onCopy={() => void copyText(invokeLog.request, "请求信息")} />
                  </Col>
                  <Col xs={24}>
                    <CodeSnippet title="供应商形状的模拟响应" value={invokeLog.response} onCopy={() => void copyText(invokeLog.response, "响应 JSON")} />
                  </Col>
                </Row>
              </div>
            )}
          </Card>
        </Col>

        <Col span={24}>
          <Card
            title="本地验证码收件箱"
            extra={activeSession ? <Tag color="green">每秒检查一次</Tag> : <Tag>会话开启后可用</Tag>}
          >
            {!activeSession ? (
              <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                {canControl ? "开启短信调试并调用本地模拟接口后，新的合成验证码会在这里出现并弹窗。" : "当前账号没有私有会话控制权限，因此不会读取会话消息或验证码。"}
              </Typography.Paragraph>
            ) : (
              <>
                {messages.isError && <Alert showIcon type="error" style={{ marginBottom: 12 }} message="读取本地收件箱失败，请检查本地接收服务。" />}
                {messages.data?.items.some(item => item.recipient !== DEBUG_RECIPIENT) && (
                  <Alert showIcon type="warning" style={{ marginBottom: 12 }} message="收件箱包含非固定测试号码的消息，已隐藏这些消息和验证码。" />
                )}
                <Table<DebuggerMessage>
                  size="small"
                  rowKey="id"
                  loading={messages.isFetching}
                  dataSource={safeMessages}
                  pagination={{ pageSize: 5, showSizeChanger: false }}
                  scroll={{ x: 720 }}
                  locale={{ emptyText: "尚未收到合成测试验证码" }}
                  columns={[
                    { title: "序号", key: "number", width: 72, render: (_value, _row, index) => index + 1 },
                    { title: "接收时间", dataIndex: "receivedAt", width: 180, render: (value: string) => formatDate(value) },
                    { title: "测试号码", dataIndex: "recipient", width: 150 },
                    { title: "来源", dataIndex: "provider", width: 130 },
                    {
                      title: "消息内容（已隐藏验证码）",
                      key: "message",
                      render: (_value, row) => <Typography.Text style={{ display: "block", maxWidth: 520, whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{maskMessage(row)}</Typography.Text>,
                    },
                    {
                      title: "操作",
                      key: "actions",
                      width: 130,
                      render: (_value, row) => row.code ? <Button type="link" onClick={() => setActiveCode(row)}>查看验证码</Button> : null,
                    },
                  ]}
                />
              </>
            )}
          </Card>
        </Col>
      </Row>

      <Modal
        open={Boolean(activeCode && activeSession && activeCode.recipient === DEBUG_RECIPIENT)}
        title="收到本地测试验证码"
        onCancel={closeCodeModal}
        onOk={closeCodeModal}
        okText="关闭"
        cancelButtonProps={{ style: { display: "none" } }}
        destroyOnHidden
      >
        {activeCode && (
          <Space direction="vertical" size={12} style={{ width: "100%" }}>
            <Alert showIcon type="success" message="这是本地合成验证码，不是供应商真实投递。" />
            <Typography.Text type="secondary">测试号码：{DEBUG_RECIPIENT}</Typography.Text>
            <Typography.Text type="secondary">接收时间：{formatDate(activeCode.receivedAt)}</Typography.Text>
            <Typography.Paragraph
              copyable={{ text: activeCode.code }}
              style={{ margin: 0, padding: 18, borderRadius: 10, background: "rgba(127, 127, 127, 0.08)", textAlign: "center", fontSize: 28, fontWeight: 700, letterSpacing: 6 }}
            >
              {activeCode.code}
            </Typography.Paragraph>
          </Space>
        )}
      </Modal>
    </div>
  );
}

function CodeSnippet({ title, value, onCopy }: { title: string; value: string; onCopy: () => void }) {
  return (
    <div style={{ minWidth: 0 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12, marginBottom: 6 }}>
        <Typography.Text strong>{title}</Typography.Text>
        <Button size="small" onClick={onCopy}>复制</Button>
      </div>
      <pre style={{ margin: 0, padding: 12, borderRadius: 8, background: "rgba(127, 127, 127, 0.08)", whiteSpace: "pre-wrap", overflowWrap: "anywhere", wordBreak: "break-word", maxHeight: 240, overflow: "auto" }}>
        {value}
      </pre>
    </div>
  );
}
