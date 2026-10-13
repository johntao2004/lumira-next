"use client";

import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Button, ConfigProvider, Input, InputNumber, Select, Switch, Tag, Typography } from "antd";
import { HolderOutlined, PlusOutlined } from "@ant-design/icons";
import { closestCenter, DndContext, KeyboardSensor, PointerSensor, useSensor, useSensors } from "@dnd-kit/core";
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { api } from "@/platform/api/client";
import type { PageResult } from "@/platform/api/pagination";
import { SettingsSection } from "@/platform/components/settings-section";
import { usePreferences } from "@/platform/providers";
import { localizeProfileFieldEditorValue, localizeProfileFieldGroupLabel, localizeProfileFieldLabel } from "@/platform/profile-fields/localization";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { mergeProfileFieldSettings } from "./profile-field-submission";

const types = [["TEXT", "fieldText"], ["TEXTAREA", "fieldTextarea"], ["NUMBER", "fieldNumber"], ["DATE", "fieldDate"], ["SELECT", "fieldSelect"]] as const;

type ProfilePlacement = {
  page: "account-profile" | "admin-user-editor" | "public-registration";
  tab: "basic" | "details" | "custom" | "profile" | "additional";
  sortNo: number;
};

const placementTargets = [
  { value: "account-profile:basic", labelKey: "profilePlacementAccountBasic", page: "account-profile", tab: "basic" },
  { value: "account-profile:details", labelKey: "profilePlacementAccountDetails", page: "account-profile", tab: "details" },
  { value: "account-profile:custom", labelKey: "profilePlacementAccountCustom", page: "account-profile", tab: "custom" },
  { value: "admin-user-editor:profile", labelKey: "profilePlacementAdminProfile", page: "admin-user-editor", tab: "profile" },
  { value: "public-registration:additional", labelKey: "profilePlacementRegistrationAdditional", page: "public-registration", tab: "additional" },
] as const;

type ProfileField = {
  key: string; label: string; type: string; custom: boolean; visible: boolean;
  required: boolean; sortNo: number; groupKey: string; groupLabel: string;
  publicRegistrationAllowed?: boolean; placements?: ProfilePlacement[];
};

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
      return api<PageResult<DictionaryOption>>(`/platform/settings/profile-fields/dictionaries?${params}`, { signal });
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
  const placements: ProfilePlacement[] = value?.placements ?? legacyPlacements(value);
  const selectedTargets = placements.map(placement => `${placement.page}:${placement.tab}`);
  const registrationOnly = placements.length > 0 && placements.every(placement => placement.page === "public-registration");
  const textFields = [["label", "fieldLabel", 64], ["key", "fieldKey", 64], ["description", "fieldDescription", 200], ["placeholder", "fieldPlaceholder", 120], ["groupKey", "fieldGroupKey", 64], ["groupLabel", "fieldGroupLabel", 64]] as const;

  return <div className="profile-field-editor-grid">
    <Form.Item name={[index, "custom"]} hidden valuePropName="checked"><Switch /></Form.Item>
    {textFields.map(([name, label, max]) => <Form.Item
      key={name}
      name={[index, name]}
      label={t(label)}
      getValueProps={inputValue => ({
        value: name === "label" || name === "groupLabel"
          ? localizeProfileFieldEditorValue({
            key: value?.key ?? "",
            label: value?.label ?? "",
            groupKey: value?.groupKey ?? "",
            groupLabel: value?.groupLabel ?? "",
            custom,
          }, name, inputValue, t)
          : inputValue,
      })}
      rules={[
        ...(["key", "label", "groupKey", "groupLabel"].includes(name) ? [{ required: true, whitespace: true, message: t("invalidProfileFields") }] : []),
        ...(["key", "groupKey"].includes(name) ? [{ pattern: /^[A-Za-z][A-Za-z0-9_]{0,63}$/, message: t("invalidProfileFields") }] : []),
      ]}
    ><Input maxLength={max} disabled={name === "key" && !custom ? true : undefined} /></Form.Item>)}
    <Form.Item name={[index, "type"]} label={t("fieldType")}>
      <Select
        disabled={!custom ? true : undefined}
        options={[...types.map(([type, label]) => ({ value: type, label: t(label) })), ...(value?.key === "avatarUrl" ? [{ value: "IMAGE", label: t("fieldImage") }] : [])]}
        onChange={type => {
          if (type !== "SELECT") {
            form.setFieldValue(["data", "fields", index, "options"], []);
            form.setFieldValue(["data", "fields", index, "dictionaryCode"], "");
          }
        }}
      />
    </Form.Item>
    <Form.Item name={[index, "weight"]} label={t("fieldWeight")}><InputNumber min={1} max={100} precision={0} /></Form.Item>
    <Form.Item name={[index, "sortNo"]} hidden><InputNumber /></Form.Item>
    <Form.Item name={[index, "visible"]} label={t("fieldVisible")} valuePropName="checked"><Switch disabled={primary ? true : undefined} /></Form.Item>
    <Form.Item name={[index, "required"]} label={t("fieldRequired")} valuePropName="checked" rules={[{ validator: async (_, required) => {
      if (custom && value?.visible && required && (placements.length === 0 || registrationOnly)) throw new Error(t("invalidProfileFields"));
    } }]}><Switch disabled={primary ? true : undefined} /></Form.Item>
    <Form.Item label={t("profilePlacement")} extra={t("profilePlacementRegistrationOptionalHint")}>
      <Select
        mode="multiple"
        value={selectedTargets}
        options={placementTargets.map(target => ({
          value: target.value,
          label: t(target.labelKey),
          disabled: target.page === "public-registration"
            ? !custom
            : primary
              ? target.page === "admin-user-editor" ? target.tab !== "profile" : target.tab !== "basic"
              : target.page === "admin-user-editor" ? !custom : false,
        }))}
        onChange={(next: string[]) => {
          const nextTargets = primary
            ? [...new Set([...next, "account-profile:basic", "admin-user-editor:profile"])]
            : next;
          const previousByTarget = new Map(placements.map(placement => [`${placement.page}:${placement.tab}`, placement]));
          const selected = new Set(nextTargets);
          const previouslySelected = new Set(selectedTargets);
          const byPage = new Map<string, string>();
          for (const page of [...new Set(placementTargets.map(target => target.page))]) {
            const newChoice = nextTargets.find(targetKey => {
              const target = placementTargets.find(candidate => candidate.value === targetKey);
              return target?.page === page && !previouslySelected.has(targetKey);
            });
            const currentChoice = placements.find(placement => placement.page === page);
            const currentKey = currentChoice ? `${currentChoice.page}:${currentChoice.tab}` : undefined;
            const retainedChoice = currentKey && selected.has(currentKey) ? currentKey : undefined;
            const fallbackChoice = nextTargets.find(targetKey => placementTargets.find(candidate => candidate.value === targetKey)?.page === page);
            const choice = newChoice ?? retainedChoice ?? fallbackChoice;
            if (choice) byPage.set(page, choice);
          }
          // Selecting another tab in one host replaces its previous tab; each field has one name per host.
          const normalized = [...byPage.values()].map(targetKey => {
            const target = placementTargets.find(candidate => candidate.value === targetKey)!;
            const previous = previousByTarget.get(targetKey);
            return { page: target.page, tab: target.tab, sortNo: previous?.sortNo ?? value?.sortNo ?? 10 } as ProfilePlacement;
          });
          form.setFieldValue(["data", "fields", index, "placements"], normalized);
          if (!normalized.some(placement => placement.page === "public-registration")) {
            form.setFieldValue(["data", "fields", index, "publicRegistrationAllowed"], false);
          }
        }}
      />
    </Form.Item>
    {custom && selectedTargets.includes("public-registration:additional") && <Form.Item
      name={[index, "publicRegistrationAllowed"]}
      label={t("profilePlacementPublicAllowed")}
      valuePropName="checked"
    ><Switch /></Form.Item>}
    {selectedTargets.includes("public-registration:additional") && <Typography.Paragraph type="secondary">{t("profilePlacementRegistrationOptionalHint")}</Typography.Paragraph>}
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

function legacyPlacements(value?: ProfileField): ProfilePlacement[] {
  if (!value) return [];
  const sortNo = value.sortNo ?? 10;
  if (value.custom) return [{ page: "account-profile", tab: "custom", sortNo }];
  if (value.key === "displayName") return [
    { page: "account-profile", tab: "basic", sortNo },
    { page: "admin-user-editor", tab: "profile", sortNo },
  ];
  return [{ page: "account-profile", tab: "basic", sortNo }];
}

function SortableField({ id, index, value, selected, disabled, onSelect }: {
  id: string; index: number; value?: ProfileField; selected: boolean; disabled: boolean;
  onSelect: () => void;
}) {
  const { t } = usePreferences();
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, isDragging } = useSortable({ id, disabled, transition: null });
  const label = value?.label ? localizeProfileFieldLabel(value, t) : t("fieldLabel");
  const groupLabel = value ? localizeProfileFieldGroupLabel(value, t) : "";
  const type = value?.type === "IMAGE" ? t("fieldImage") : t(types.find(([type]) => type === value?.type)?.[1] ?? "fieldText");
  return <div
    ref={setNodeRef}
    data-field-key={value?.key}
    className={`profile-field-row${selected ? " is-selected" : ""}${isDragging ? " is-dragging" : ""}`}
    style={{ transform: CSS.Transform.toString(transform) }}
  >
    <Button ref={setActivatorNodeRef} type="text" className="profile-field-drag" icon={<HolderOutlined />} disabled={disabled}
      {...attributes} {...listeners} aria-label={`${t("profileFieldDrag")} · ${label}`} />
    <button type="button" className="profile-field-select" aria-pressed={selected} onClick={onSelect}>
      <span className="profile-field-label"><span className="profile-field-position">{index + 1}</span><strong>{label}</strong></span>
      <span className="profile-field-meta">{type} · {groupLabel}</span>
      <span className="profile-field-tags">
        <Tag>{t(value?.custom ? "profileFieldCustom" : "profileFieldBuiltIn")}</Tag>
        {value?.required && <Tag color="blue">{t("fieldRequired")}</Tag>}
        {!value?.visible && <Tag>{t("profileFieldHidden")}</Tag>}
      </span>
    </button>
  </div>;
}

function FieldList({ canReadDictionaries, selectedIndex, onSelect }: {
  canReadDictionaries: boolean; selectedIndex: number; onSelect: (index: number) => void;
}) {
  const { t } = usePreferences();
  const form = Form.useFormInstance();
  const values: ProfileField[] = Form.useWatch(["data", "fields"], { form, preserve: true }) ?? [];
  const { componentDisabled } = ConfigProvider.useConfig();
  const disabled = !!componentDisabled;
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  return <Form.List name={["data", "fields"]}>{(fields, { add, remove, move }) => {
    const activeIndex = Math.min(selectedIndex, Math.max(0, fields.length - 1));
    const reorder = (from: number, to: number) => {
      if (disabled || from === to || to < 0 || to >= fields.length) return;
      move(from, to);
      // Persist exactly the displayed order through the schema's existing sort numbers.
      const reordered: ProfileField[] = form.getFieldValue(["data", "fields"]);
      const placementIndexes = new Map<string, number>();
      reordered.forEach((field, index) => {
        form.setFieldValue(["data", "fields", index, "sortNo"], (index + 1) * 10);
        const placements = field.placements ?? legacyPlacements(field);
        const nextPlacements = placements.map(placement => {
          const target = `${placement.page}:${placement.tab}`;
          const sortNo = (placementIndexes.get(target) ?? 0) * 10 + 10;
          placementIndexes.set(target, sortNo / 10);
          return { ...placement, sortNo };
        });
        form.setFieldValue(["data", "fields", index, "placements"], nextPlacements);
      });
      onSelect(activeIndex === from ? to : from < activeIndex && to >= activeIndex ? activeIndex - 1 : from > activeIndex && to <= activeIndex ? activeIndex + 1 : activeIndex);
    };
    return <div className="profile-fields-workspace">
      <aside className="profile-fields-list" aria-label={t("profileFieldOrder")}>
        <div className="profile-fields-heading">
          <Typography.Text strong>{t("profileFieldOrder")}</Typography.Text>
          <Typography.Text type="secondary">{fields.length} / 100</Typography.Text>
        </div>
        <Typography.Paragraph type="secondary" className="profile-fields-hint">{t("profileFieldOrderHint")}</Typography.Paragraph>
        <DndContext sensors={sensors} collisionDetection={closestCenter}
          accessibility={{ screenReaderInstructions: { draggable: t("profileFieldKeyboardHint") }, announcements: {
            onDragStart: () => t("profileFieldDrag"),
            onDragOver: ({ over }) => over ? t("profileFieldPosition", { position: fields.findIndex(field => String(field.key) === over.id) + 1 }) : undefined,
            onDragEnd: ({ over }) => over ? t("profileFieldPosition", { position: fields.findIndex(field => String(field.key) === over.id) + 1 }) : undefined,
            onDragCancel: () => t("cancel"),
          } }}
          onDragEnd={({ active, over }) => {
            if (over) reorder(fields.findIndex(field => String(field.key) === active.id), fields.findIndex(field => String(field.key) === over.id));
          }}
        >
          <SortableContext items={fields.map(field => String(field.key))} strategy={verticalListSortingStrategy}>
            <div className="profile-fields-rows">
              {fields.map((field, index) => <SortableField key={field.key} id={String(field.key)} index={index} value={values[field.name]}
                selected={index === activeIndex} disabled={disabled} onSelect={() => onSelect(index)} />)}
            </div>
          </SortableContext>
        </DndContext>
        <Button block icon={<PlusOutlined />} aria-label={t("addProfileField")} className="profile-fields-add" disabled={disabled || fields.length >= 100} onClick={() => {
          const sortNo = Math.max(0, ...values.map(field => field.sortNo)) + 10;
          add({ key: createProfileFieldKey(), label: "", description: "", type: "TEXT", visible: true, required: false, weight: 5, placeholder: "", groupKey: "custom", groupLabel: values.find(field => field.groupKey === "custom")?.groupLabel ?? t("fieldCustomGroup"), sortNo, custom: true, options: [], dictionaryCode: "", publicRegistrationAllowed: false, placements: [{ page: "account-profile", tab: "custom", sortNo }] });
          onSelect(fields.length);
        }}>{t("addProfileField")}</Button>
      </aside>
      <section className="profile-fields-detail" aria-label={t("profileFieldSettings")}>
        <div className="profile-fields-detail-heading">
          <div>
            <Typography.Text type="secondary">{t("profileFieldSettings")}</Typography.Text>
            <Typography.Title level={5}>{values[activeIndex]?.label ? localizeProfileFieldLabel(values[activeIndex], t) : t("addProfileField")}</Typography.Title>
          </div>
          <Tag>{t(values[activeIndex]?.custom ? "profileFieldCustom" : "profileFieldBuiltIn")}</Tag>
        </div>
        {fields.map(field => <div key={field.key} hidden={field.name !== activeIndex}>
          <FieldEditor index={field.name} canReadDictionaries={canReadDictionaries} />
          {values[field.name]?.custom && <Button danger disabled={disabled} onClick={() => {
            remove(field.name);
            onSelect(Math.max(0, field.name - 1));
          }}>{t("deleteRecord")}</Button>}
        </div>)}
      </section>
    </div>;
  }}</Form.List>;
}

export function ProfileFieldsPage({ canWrite, canReadDictionaries }: { canWrite: boolean; canReadDictionaries: boolean }) {
  const [selectedIndex, setSelectedIndex] = useState(0);
  return <SettingsSection section="profile-fields" className="profile-fields-settings" canWrite={canWrite} showSecondaryActions={false} prepareSubmission={({ submitted, formValues, snapshot }) => {
    const submittedData = (submitted.data ?? {}) as Record<string, unknown>;
    const formData = (formValues.data ?? {}) as Record<string, unknown>;
    const currentFields = Array.isArray(formData.fields) ? formData.fields : submittedData.fields;
    const currentData = { ...submittedData, ...formData, ...(Array.isArray(currentFields) ? { fields: currentFields } : {}) };
    return { ...submitted, data: mergeProfileFieldSettings(snapshot.data as Record<string, unknown>, currentData) };
  }} onFinishFailed={({ errorFields }) => {
    const index = errorFields[0]?.name[2];
    if (typeof index === "number") setSelectedIndex(index);
  }}><FieldList canReadDictionaries={canReadDictionaries} selectedIndex={selectedIndex} onSelect={setSelectedIndex} /></SettingsSection>;
}
