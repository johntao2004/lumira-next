"use client";

import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { App, Button, Card, Descriptions, Empty, Form, Input, Progress, Select, Space, Table, Typography } from "antd";
import { api, mutate } from "@/platform/api/client";
import { isMessageKey } from "@/platform/i18n/messages";
import { usePreferences } from "@/platform/providers";
import { QueryFailure } from "@/platform/components/query-failure";
import { DEFAULT_TABLE_PAGE_SIZE, TABLE_PAGE_SIZE_OPTIONS } from "@/platform/api/pagination";
import type { Provider } from "./types";

type JudgmentType = "CHOICE" | "SCORE" | "NOUL";
type JudgmentForm = { providerId?: string; type: JudgmentType; state: string; instructions: string; choices?: string; scoreLevels?: string; yesMeaning?: string; noMeaning?: string };
type JudgmentResponse = { model?: string; answers?: Record<string, Record<string, unknown>>; usage?: { input_tokens?: number; output_tokens?: number } };

export function AiJudgmentPage({ canWrite }: { canWrite: boolean }) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const [form] = Form.useForm<JudgmentForm>();
  const [result, setResult] = useState<JudgmentResponse | null>(null);
  const [running, setRunning] = useState(false);
  const type = Form.useWatch("type", form) ?? "CHOICE";
  const providers = useQuery({ queryKey: ["ai-providers"], queryFn: ({ signal }) => api<Provider[]>("/platform/ai/providers", { signal }) });
  const jevProviders = useMemo(() => (providers.data ?? []).filter((provider) => provider.enabled && provider.providerKind === "TYPESAFE_JEV"), [providers.data]);
  useEffect(() => {
    if (!form.getFieldValue("providerId") && jevProviders.length > 0) form.setFieldValue("providerId", jevProviders[0].id);
  }, [form, jevProviders]);

  const run = async (values: JudgmentForm) => {
    if (!canWrite || !values.providerId) return;
    setRunning(true); setResult(null);
    let choices: Record<string, string> | undefined;
    let scoreLevels: string[] | undefined;
    if (values.type === "CHOICE") {
      choices = Object.fromEntries((values.choices ?? "").split("\n").map((line) => line.trim()).filter(Boolean).map((line) => {
        const separator = line.indexOf("|");
        return separator < 1 ? [line, ""] : [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
      }));
    }
    if (values.type === "SCORE") scoreLevels = (values.scoreLevels ?? "").split("\n").map((line) => line.trim()).filter(Boolean);
    try {
      const response = await mutate<JudgmentResponse>("/platform/ai/judgments", "POST", {
        providerId: values.providerId, state: values.state, type: values.type, instructions: values.instructions,
        choices, scoreLevels, yesMeaning: values.yesMeaning, noMeaning: values.noMeaning,
      });
      setResult(response);
    } catch (error) {
      const key = error instanceof Error && isMessageKey(error.message) ? error.message : "unavailable";
      message.error(t(key));
    } finally { setRunning(false); }
  };

  const answer = result?.answers?.decision;
  const probabilities = answer?.probabilities && typeof answer.probabilities === "object" ? Object.entries(answer.probabilities as Record<string, number>) : [];
  const legend = answer?.legend && typeof answer.legend === "object" ? answer.legend as Record<string, string> : {};
  return <Space orientation="vertical" size="middle" className="w-full">
    {providers.error && <QueryFailure title={t("unavailable")} pending={providers.isFetching} onRetry={() => void providers.refetch()} />}
    {!canWrite && <Typography.Text type="secondary">{t("forbidden")}</Typography.Text>}
    {canWrite && jevProviders.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("aiNoProviders")} />}
    <Card title={t("aiJudgment")} extra={<Typography.Text type="secondary">{t("aiJevFixedEndpoint")}</Typography.Text>}>
      <Form<JudgmentForm> form={form} layout="vertical" initialValues={{ type: "CHOICE", state: "Synthetic support ticket: A customer reports that account sign-in failed several times today.", instructions: "Which team should handle this request?", choices: "account_access|Sign-in, identity, or account recovery\ntechnical|A software defect or service outage\nbilling|Payments, invoices, or refunds", scoreLevels: "Low\nModerate\nHigh" }} onFinish={(values) => void run(values)}>
        <Form.Item name="providerId" label={t("aiJudgmentProvider")} rules={[{ required: true }]}><Select options={jevProviders.map((provider) => ({ value: provider.id, label: `${provider.name} · ${provider.modelName}` }))} disabled={!canWrite || jevProviders.length === 0} /></Form.Item>
        <Form.Item name="type" label={t("aiJudgmentType")} rules={[{ required: true }]}><Select disabled={!canWrite} options={[
          { value: "CHOICE", label: t("aiJudgmentChoice") }, { value: "SCORE", label: t("aiJudgmentScore") }, { value: "NOUL", label: t("aiJudgmentNoul") },
        ]} /></Form.Item>
        <Form.Item name="state" label={t("aiJudgmentState")} rules={[{ required: true, max: 8000 }]}><Input.TextArea rows={4} maxLength={8000} showCount disabled={!canWrite} /></Form.Item>
        <Form.Item name="instructions" label={t("aiJudgmentInstructions")} rules={[{ required: true, max: 2000 }]}><Input.TextArea rows={3} maxLength={2000} showCount disabled={!canWrite} /></Form.Item>
        {type === "CHOICE" && <Form.Item name="choices" label={t("aiJudgmentChoices")} rules={[{ required: true }]}><Input.TextArea rows={4} disabled={!canWrite} /></Form.Item>}
        {type === "SCORE" && <Form.Item name="scoreLevels" label={t("aiJudgmentLevels")} rules={[{ required: true }]}><Input.TextArea rows={4} disabled={!canWrite} /></Form.Item>}
        {type === "NOUL" && <>
          <Form.Item name="yesMeaning" label={t("aiJudgmentYes")}><Input disabled={!canWrite} maxLength={500} /></Form.Item>
          <Form.Item name="noMeaning" label={t("aiJudgmentNo")}><Input disabled={!canWrite} maxLength={500} /></Form.Item>
        </>}
        <Button type="primary" htmlType="submit" loading={running} disabled={!canWrite || jevProviders.length === 0}>{t("aiRunJudgment")}</Button>
      </Form>
    </Card>
    {answer && <Card title={t("aiResult")}>
      <Descriptions column={{ xs: 1, sm: 2 }} items={[
        { key: "model", label: t("aiModelName"), children: result?.model },
        ...(typeof answer.confidence === "number" ? [{ key: "confidence", label: t("aiConfidence"), children: <Progress percent={Math.round(answer.confidence * 100)} size="small" /> }] : []),
        ...(typeof answer.choice === "string" ? [{ key: "choice", label: t("aiJudgmentChoice"), children: answer.choice }] : []),
        ...(typeof answer.score === "number" ? [{ key: "score", label: t("aiJudgmentScore"), children: answer.score }] : []),
        ...(typeof answer.noul === "number" ? [{ key: "noul", label: t("aiJudgmentNoul"), children: `${Math.round(answer.noul * 100)}%` }] : []),
        ...(result?.usage ? [{ key: "usage", label: t("aiTokenUsage"), children: `${result.usage.input_tokens ?? 0} / ${result.usage.output_tokens ?? 0}` }] : []),
      ]} />
      {probabilities.length > 0 && <Table size="small" rowKey="key" pagination={{ defaultPageSize: DEFAULT_TABLE_PAGE_SIZE, showSizeChanger: true, pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS] }} dataSource={probabilities.map(([key, probability]) => ({ key, probability, description: legend[key] ?? key }))} columns={[
        { title: t("name"), dataIndex: "description" }, { title: t("aiConfidence"), dataIndex: "probability", render: (value: number) => `${Math.round(value * 100)}%` },
      ]} />}
    </Card>}
  </Space>;
}
