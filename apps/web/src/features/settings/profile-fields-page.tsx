"use client";

import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Button, Collapse, ConfigProvider, Input, InputNumber, Select, Space, Switch } from "antd";
import { api } from "@/platform/api/client";
import type { PageResult } from "@/platform/api/pagination";
import { SettingsSection } from "@/platform/components/settings-section";
import { usePreferences } from "@/platform/providers";
import { PlatformForm as Form } from "@/platform/components/platform-form";

const types = [["TEXT", "fieldText"], ["TEXTAREA", "fieldTextarea"], ["NUMBER", "fieldNumber"], ["DATE", "fieldDate"], ["SELECT", "fieldSelect"]] as const;

type DictionaryOption = { code: string; name: string; enabled: boolean };

function createProfileFieldKey() {
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16));
  return `profileField_${[...bytes].map(byte => byte.toString(16).padStart(2, "0")).join("")}`;
}

function DictionaryCodeControl({
  canReadDictionaries,
  active,
  onDictionarySelected,
  value,
  onChange,
}: {
  canReadDictionaries: boolean;
  active: boolean;
  onDictionarySelected: () => void;
  value?: string;
  onChange?: (value: string) => void;
}) {
  const { t } = usePreferences();
  const [searchTerm, setSearchTerm] = useState("");
  const dictionaries = useInfiniteQuery({
    queryKey: ["profile-field-dictionaries", searchTerm],
    enabled: canReadDictionaries && active,
    initialPageParam: 1,
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({
        page: String(pageParam),
        pageSize: "20",
        search: searchTerm,
        "filter.enabled": "true",
        "filterMode.enabled": "exact",
      });
      return api<PageResult<DictionaryOption>>(`/platform/dictionaries?${params}`, { signal });
    },
    getNextPageParam: page => page.page * page.pageSize < page.total ? page.page + 1 : undefined,
  });

  const updateCode = (nextValue: string | undefined) => {
    const next = nextValue ?? "";
    if (next) onDictionarySelected();
    onChange?.(next);
  };

  // Keep the original manual entry available if the role cannot list dictionaries
  // or if the read request fails. The current binding is never discarded here.
  if (!canReadDictionaries || dictionaries.isError) {
    return <Input maxLength={100} value={value} onChange={event => updateCode(event.target.value)} />;
  }

  const options: { value: string; label: string; disabled?: boolean }[] = (dictionaries.data?.pages ?? [])
    .flatMap(page => page.items)
    .filter(dictionary => dictionary.enabled)
    .map(dictionary => ({
      value: dictionary.code,
      label: `${dictionary.name} (${dictionary.code})`,
    }));

  // Keep an existing code visible and unchanged when it is disabled, outside
  // the current search, or beyond the pages loaded so far.
  if (value && !options.some(option => option.value === value)) {
    options.unshift({ value, label: value, disabled: true });
  }

  return <Select
    allowClear
    showSearch={{ filterOption: false, onSearch: setSearchTerm }}
    placeholder={t("searchCodes")}
    value={value || undefined}
    loading={dictionaries.isFetching}
    options={options}
    onChange={updateCode}
    popupRender={menu => <>
      {menu}
      {dictionaries.hasNextPage && <Button
        block
        loading={dictionaries.isFetchingNextPage}
        onMouseDown={event => event.preventDefault()}
        onClick={() => { void dictionaries.fetchNextPage(); }}
      >{t("loadMore")}</Button>}
    </>}
  />;
}

function FieldEditor({ index, canReadDictionaries }: { index: number; canReadDictionaries: boolean }) {
  const { t } = usePreferences();
  const form = Form.useFormInstance();
  const value = Form.useWatch(["data", "fields", index], { form, preserve: true });
  const custom = value?.custom === true;
  const primary = value?.key === "displayName";
  const textFields = [["label", "fieldLabel", 64], ["key", "fieldKey", 64], ["description", "fieldDescription", 200], ["placeholder", "fieldPlaceholder", 120], ["groupKey", "fieldGroupKey", 64], ["groupLabel", "fieldGroupLabel", 64]] as const;

  return <div className="grid grid-cols-1 gap-x-4 md:grid-cols-2">
    <Form.Item name={[index, "custom"]} hidden valuePropName="checked"><Switch /></Form.Item>
    {textFields.map(([name, label, max]) => <Form.Item
      key={name}
      name={[index, name]}
      label={t(label)}
      rules={["key", "label", "groupKey", "groupLabel"].includes(name) ? [{ required: true, whitespace: true, message: t("invalidProfileFields") }] : []}
    ><Input maxLength={max} disabled={name === "key" ? true : undefined} /></Form.Item>)}
    <Form.Item name={[index, "type"]} label={t("fieldType")}>
      <Select
        disabled={!custom ? true : undefined}
        options={[...types.map(([type, label]) => ({ value: type, label: t(label) })), ...(value?.key === "avatarUrl" ? [{ value: "IMAGE", label: t("avatar") }] : [])]}
        onChange={type => {
          if (type !== "SELECT") {
            form.setFieldValue(["data", "fields", index, "options"], []);
            form.setFieldValue(["data", "fields", index, "dictionaryCode"], "");
          }
        }}
      />
    </Form.Item>
    <Form.Item name={[index, "weight"]} label={t("fieldWeight")}><InputNumber min={1} max={100} precision={0} /></Form.Item>
    <Form.Item name={[index, "sortNo"]} label={t("fieldSort")}><InputNumber min={0} max={100000} precision={0} /></Form.Item>
    <Form.Item name={[index, "visible"]} label={t("fieldVisible")} valuePropName="checked"><Switch disabled={primary ? true : undefined} /></Form.Item>
    <Form.Item name={[index, "required"]} label={t("fieldRequired")} valuePropName="checked"><Switch disabled={primary ? true : undefined} /></Form.Item>
    <Form.Item
      hidden={value?.type !== "SELECT"}
      name={[index, "dictionaryCode"]}
      label={t("dictionaryCode")}
      extra={t("profileDictionaryHint")}
    >
      <DictionaryCodeControl
        canReadDictionaries={canReadDictionaries}
        active={value?.type === "SELECT"}
        onDictionarySelected={() => form.setFieldValue(["data", "fields", index, "options"], [])}
      />
    </Form.Item>
    <Form.Item
      hidden={value?.type !== "SELECT" || !!value?.dictionaryCode}
      name={[index, "options"]}
      label={t("fieldOptions")}
      getValueProps={value => ({ value: (value ?? []).join("\n") })}
      getValueFromEvent={event => event.target.value.split("\n")}
    ><Input.TextArea rows={5} /></Form.Item>
  </div>;
}

function FieldList({ canReadDictionaries }: { canReadDictionaries: boolean }) {
  const { t } = usePreferences();
  const form = Form.useFormInstance();
  const values = Form.useWatch(["data", "fields"], { form, preserve: true }) ?? [];
  const { componentDisabled } = ConfigProvider.useConfig();

  return <Form.List name={["data", "fields"]}>{(fields, { add, remove }) => <>
    <Collapse className="mb-4" items={fields.map(field => ({
      key: field.key,
      label: values[field.name]?.label || t("fieldLabel"),
      forceRender: true,
      children: <>
        <FieldEditor index={field.name} canReadDictionaries={canReadDictionaries} />
        {values[field.name]?.custom && <Button danger disabled={componentDisabled} onClick={() => remove(field.name)}>{t("deleteRecord")}</Button>}
      </>,
    }))} />
    {!values.some((field: { key: string }) => field.key === "avatarUrl") && <Button
      className="mb-4"
      disabled={componentDisabled || fields.length >= 100}
      onClick={() => add({ key: "avatarUrl", label: t("avatar"), description: "", type: "IMAGE", visible: true, required: false, weight: 5, placeholder: "", groupKey: "basic", groupLabel: values.find((field: { groupKey: string }) => field.groupKey === "basic")?.groupLabel ?? "Basic information", sortNo: 50, custom: false, options: [] })}
    >{t("addProfileField")} · {t("avatar")}</Button>}
    <Space className="mb-4">
      <Button
        disabled={componentDisabled || fields.length >= 100}
        onClick={() => add({ key: createProfileFieldKey(), label: "", description: "", type: "TEXT", visible: true, required: false, weight: 5, placeholder: "", groupKey: "custom", groupLabel: values.find((field: { groupKey: string; groupLabel: string }) => field.groupKey === "custom")?.groupLabel ?? t("fieldCustomGroup"), sortNo: 100 + fields.length * 10, custom: true, options: [], dictionaryCode: "" })}
      >{t("addProfileField")}</Button>
      <span>{fields.length} / 100</span>
    </Space>
  </>}</Form.List>;
}

export function ProfileFieldsPage({ canWrite, canReadDictionaries }: { canWrite: boolean; canReadDictionaries: boolean }) {
  return <SettingsSection section="profile-fields" canWrite={canWrite}><FieldList canReadDictionaries={canReadDictionaries} /></SettingsSection>;
}
