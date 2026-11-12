"use client";

import { useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ProDescriptions } from "@ant-design/pro-components";
import { Button, Table, Upload } from "antd";
import type { UploadFile, UploadProps } from "antd";
import { UploadOutlined } from "@ant-design/icons";
import { ApiError, mutate, upload } from "@/platform/api/client";
import { DEFAULT_TABLE_PAGE_SIZE, TABLE_PAGE_SIZE_OPTIONS } from "@/platform/api/pagination";
import { AsyncButton } from "@/platform/components/async-button";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";
import { FormDrawer } from "@/platform/components/form-drawer";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { useSessionScope } from "@/platform/auth/use-session-scope";
import { usePreferences } from "@/platform/providers";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { DictionaryFields, dictionaryDefaults, type DictionaryMetadata } from "./dictionary-fields";
import { errorKey } from "./dictionary-transfer";

type FilePreviewResult = {
  sha256: string;
  summary: { itemCount: number };
  rows: { value: string; label: string; parentValue: string | null }[];
};

type DatasetSummary = {
  code: string;
  name: string;
  structure: string;
  itemCount: number;
};

type Preview =
  | { kind: "file"; result: FilePreviewResult; query: string; file: File }
  | { kind: "json"; summary: DatasetSummary; data: unknown; file: File };

function getFileKind(file: File | null): "json" | "tabular" | null {
  if (!file) return null;
  const extension = file.name.split(".").pop()?.toLowerCase();
  if (extension === "json") return "json";
  if (extension === "txt" || extension === "xls" || extension === "xlsx") return "tabular";
  return null;
}

export function DictionaryFileImport() {
  const { t } = usePreferences();
  const client = useQueryClient();
  const [form] = Form.useForm<DictionaryMetadata>();
  const operationSignal = useSessionScope();
  const generation = useRef(0);
  const [open, setOpen] = useState(false);
  const [checking, setChecking] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [issues, setIssues] = useState<ApiError["issues"]>([]);
  const { pending, error, setError, run } = useSubmitAction({
    mapError: errorKey,
    onError: (_key, e) => setIssues(e instanceof ApiError ? e.issues : []),
  });

  const invalidate = () => {
    generation.current++;
    setPreview(null);
    setError(null);
    setIssues([]);
    setChecking(false);
  };

  const close = () => {
    invalidate();
    setOpen(false);
    setFile(null);
    setFileList([]);
    form.resetFields();
  };

  const beforeUpload: UploadProps["beforeUpload"] = (selected) => {
    invalidate();
    if (selected.size > 20 * 1024 * 1024) {
      setError("datasetTooLarge");
      setFile(null);
      setFileList([]);
      return Upload.LIST_IGNORE;
    }
    if (!getFileKind(selected)) {
      setError("invalidDataset");
      setFile(null);
      setFileList([]);
      return Upload.LIST_IGNORE;
    }
    setFile(selected);
    return false;
  };

  const check = async () => {
    const selected = file;
    const kind = getFileKind(selected);
    invalidate();
    const current = generation.current;
    const signal = operationSignal();
    if (signal.aborted || !selected || !kind) return;

    let values: DictionaryMetadata | undefined;
    if (kind === "tabular") {
      try {
        values = await form.validateFields();
      } catch {
        return;
      }
    }
    if (signal.aborted || current !== generation.current || selected !== file) return;

    setChecking(true);
    try {
      if (kind === "json") {
        const data: unknown = JSON.parse(await selected.text());
        if (signal.aborted || current !== generation.current || selected !== file) return;
        const summary = await mutate<DatasetSummary>("/platform/dictionaries/preview", "POST", data, signal);
        if (!signal.aborted && current === generation.current && selected === file) {
          setPreview({ kind: "json", summary, data, file: selected });
        }
      } else if (values) {
        const query = new URLSearchParams({ ...values, enabled: String(values.enabled), filename: selected.name }).toString();
        const result = await upload<FilePreviewResult>(`/platform/dictionaries/file-preview?${query}`, selected, signal);
        if (!signal.aborted && current === generation.current && selected === file) {
          setPreview({ kind: "file", result, query, file: selected });
        }
      }
    } catch (e) {
      if (!signal.aborted && current === generation.current && selected === file) {
        setError(errorKey(e));
        setIssues(e instanceof ApiError ? e.issues : []);
      }
    } finally {
      if (!signal.aborted && current === generation.current && selected === file) setChecking(false);
    }
  };

  const currentPreview = preview && preview.file === file ? preview : null;
  const fileKind = getFileKind(file);

  return (
    <>
      <Button onClick={() => setOpen(true)}>{t("importDictionary")}</Button>
      <FormDrawer
        open={open}
        title={t("importDictionary")}
        pending={pending}
        submitDisabled={!currentPreview || checking}
        onClose={close}
        formId="dictionary-file-import"
      >
        <Form
          form={form}
          id="dictionary-file-import"
          layout="vertical"
          disabled={pending}
          initialValues={dictionaryDefaults}
          onValuesChange={invalidate}
          onFinish={() => {
            const signal = operationSignal();
            return run(async () => {
              if (!currentPreview || checking || signal.aborted) return false;
              try {
                if (currentPreview.kind === "json") {
                  await mutate("/platform/dictionaries/import", "POST", currentPreview.data, signal);
                } else {
                  await upload(
                    `/platform/dictionaries/file-import?${currentPreview.query}&expectedSha256=${currentPreview.result.sha256}`,
                    currentPreview.file,
                    signal,
                  );
                }
                if (signal.aborted) return false;
                close();
                await Promise.all([
                  client.invalidateQueries({ queryKey: ["dictionaries"] }),
                  client.invalidateQueries({ queryKey: ["dictionary-options"] }),
                ]);
              } catch (error) {
                if (signal.aborted) return false;
                throw error;
              }
            });
          }}
        >
          {fileKind !== "json" && <DictionaryFields />}
          <Form.Item label={t("chooseDataset")} required extra={t("datasetHint")}>
            <Upload
              accept=".json,.txt,.xls,.xlsx,application/json,text/plain"
              maxCount={1}
              fileList={fileList}
              disabled={pending}
              beforeUpload={beforeUpload}
              onChange={({ fileList: next }) => setFileList(next.slice(-1))}
              onRemove={() => {
                invalidate();
                setFile(null);
                setFileList([]);
                return true;
              }}
            >
              <Button disabled={pending} icon={<UploadOutlined />} aria-label={t("chooseDataset")}>
                {t("uploadFile")}
              </Button>
            </Upload>
          </Form.Item>
          <AsyncButton disabled={!file || pending} pending={checking} onClick={() => void check()} label={t("previewDataset")} />
          <FeedbackBubble id="dictionary-file-import-error" content={error ? t(error) : undefined} />
          {issues.length > 0 && (
            <Table
              size="medium"
              scroll={{ x: "max-content" }}
              rowKey={(item) => `${item.row}-${item.field}-${item.reason}`}
              pagination={{ defaultPageSize: DEFAULT_TABLE_PAGE_SIZE, showSizeChanger: true, pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS] }}
              dataSource={issues}
              columns={[
                { title: "#", dataIndex: "row" },
                { title: t("name"), dataIndex: "field" },
                {
                  title: t("details"),
                  dataIndex: "reason",
                  render: (reason: string) => t(reason === "duplicate" ? "duplicateDictionaryItem" : reason === "hierarchy" ? "importHierarchy" : "importInvalidField"),
                },
              ]}
            />
          )}
          {currentPreview?.kind === "file" && (
            <>
              <p>{t("dictionaryItems")}: {currentPreview.result.summary.itemCount}</p>
              <Table
                size="medium"
                scroll={{ x: "max-content" }}
                rowKey="value"
                pagination={{ defaultPageSize: DEFAULT_TABLE_PAGE_SIZE, showSizeChanger: true, pageSizeOptions: [...TABLE_PAGE_SIZE_OPTIONS] }}
                dataSource={currentPreview.result.rows}
                columns={[{ title: t("itemValue"), dataIndex: "value" }, { title: t("name"), dataIndex: "label" }]}
              />
            </>
          )}
          {currentPreview?.kind === "json" && (
            <ProDescriptions
              column={1}
              dataSource={currentPreview.summary}
              columns={[
                { dataIndex: "code", title: t("dictionaryCode") },
                { dataIndex: "name", title: t("name") },
                {
                  dataIndex: "structure",
                  title: t("dictionaryStructure"),
                  render: () => t(currentPreview.summary.structure === "TREE" ? "treeDictionary" : "flatDictionary"),
                },
                { dataIndex: "itemCount", title: t("dictionaryItems") },
              ]}
            />
          )}
        </Form>
      </FormDrawer>
    </>
  );
}
