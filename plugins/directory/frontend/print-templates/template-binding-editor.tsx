"use client";

import { CopyOutlined } from "@ant-design/icons";
import { App, Button, Card, Empty, Select, Space, Tag, Typography } from "antd";
import { useMemo } from "react";
import { usePreferences } from "@/platform/providers";
import { userFieldLabel, userFieldSourceLabel } from "@/platform/templates/field-labels";
import type { TemplateField, TemplateSlot } from "@/platform/templates/types";
import "./template-binding-editor.css";

function columnLabel(index: number) {
  let current = index + 1;
  let label = "";
  while (current > 0) {
    const remainder = (current - 1) % 26;
    label = String.fromCharCode(65 + remainder) + label;
    current = Math.floor((current - 1) / 26);
  }
  return label;
}

function buildFieldOptions(fields: TemplateField[], t: (key: string) => string) {
  const groups = new Map<string, TemplateField[]>();
  for (const field of fields.filter((item) => item.readable)) {
    const group = groups.get(field.source) ?? [];
    group.push(field);
    groups.set(field.source, group);
  }
  return {
    options: [...groups.entries()].map(([source, items]) => ({
      label: userFieldSourceLabel(source, t),
      options: items.map((field) => ({
        value: field.key,
        label: userFieldLabel(field, t),
      })),
    })),
    searchText: Object.fromEntries(fields.filter((field) => field.readable).map((field) => [
      field.key,
      `${userFieldLabel(field, t)} ${userFieldSourceLabel(field.source, t)}`.toLocaleLowerCase(),
    ])),
  };
}

function FieldSelect({ value, ariaLabel, options, searchText, placeholder, disabled, onChange }: {
  value?: string;
  ariaLabel?: string;
  options: ReturnType<typeof buildFieldOptions>["options"];
  searchText: Record<string, string>;
  placeholder: string;
  disabled: boolean;
  onChange: (value: string | undefined) => void;
}) {
  return <Select
    allowClear
    showSearch
    filterOption={(input, option) => {
      const optionValue = (option as { value?: string } | undefined)?.value;
      return Boolean(optionValue && searchText[optionValue]?.includes(input.toLocaleLowerCase()));
    }}
    placeholder={placeholder}
    aria-label={ariaLabel}
    value={value}
    disabled={disabled}
    options={options}
    onChange={(next: string | undefined) => onChange(next)}
    style={{ width: "100%" }}
  />;
}

function WordSlotCard({ slot, field, options, searchText, placeholder, disabled, onChange }: {
  slot: TemplateSlot;
  field?: string;
  options: ReturnType<typeof buildFieldOptions>["options"];
  searchText: Record<string, string>;
  placeholder: string;
  disabled: boolean;
  onChange: (value: string | undefined) => void;
}) {
  return <article className="template-word-slot" aria-label={slot.label}>
    <div className="template-word-slot__text">
      {slot.text || <span className="template-slot-placeholder">　</span>}
    </div>
    <div className="template-word-slot__binding">
      <Typography.Text type="secondary">{slot.label}</Typography.Text>
      <FieldSelect value={field} ariaLabel={slot.label} options={options} searchText={searchText} placeholder={placeholder} disabled={disabled} onChange={onChange} />
    </div>
  </article>;
}

function ExcelSlots({ slots, bindings, options, searchText, placeholder, disabled, onChange }: {
  slots: TemplateSlot[];
  bindings: Record<string, string>;
  options: ReturnType<typeof buildFieldOptions>["options"];
  searchText: Record<string, string>;
  placeholder: string;
  disabled: boolean;
  onChange: (slotKey: string, fieldKey: string | undefined) => void;
}) {
  const { t } = usePreferences();
  const sheetGroups = useMemo(() => {
    const grouped = new Map<number, Array<{ slot: TemplateSlot; row: number; column: number }>>();
    for (const slot of slots) {
      const match = /^xlsx:s(\d+):r(\d+):c(\d+)$/.exec(slot.key);
      if (!match) continue;
      const sheet = Number(match[1]);
      const row = Number(match[2]);
      const column = Number(match[3]);
      const cells = grouped.get(sheet) ?? [];
      cells.push({ slot, row, column });
      grouped.set(sheet, cells);
    }
    return [...grouped.entries()].sort(([left], [right]) => left - right).map(([sheet, cells]) => {
      const columns = [...new Set(cells.map((cell) => cell.column))].sort((left, right) => left - right);
      const rows = new Map<number, Map<number, TemplateSlot>>();
      for (const cell of cells) {
        const row = rows.get(cell.row) ?? new Map<number, TemplateSlot>();
        row.set(cell.column, cell.slot);
        rows.set(cell.row, row);
      }
      return { sheet, columns, rows: [...rows.entries()].sort(([left], [right]) => left - right) };
    });
  }, [slots]);

  if (!sheetGroups.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("templateNoSlots")} />;
  return <div className="template-excel-sheets">
    {sheetGroups.map(({ sheet, columns, rows }) => <section className="template-excel-sheet" key={sheet} aria-label={t("templateSheetNumber", { number: sheet + 1 })}>
      <div className="template-excel-sheet__tab">{t("templateSheetNumber", { number: sheet + 1 })}</div>
      <div className="template-excel-sheet__scroll">
        <table className="template-excel-grid">
          <thead><tr><th aria-hidden="true" />{columns.map((column) => <th key={column}>{columnLabel(column)}</th>)}</tr></thead>
          <tbody>{rows.map(([row, cells]) => <tr key={row}>
            <th scope="row">{row + 1}</th>
            {columns.map((column) => {
              const slot = cells.get(column);
              return <td key={column}>{slot && <div className="template-excel-cell" data-bound={Boolean(bindings[slot.key])}>
                <Typography.Text className="template-excel-cell__location" type="secondary">{slot.label || `${columnLabel(column)}${row + 1}`}</Typography.Text>
                <FieldSelect value={bindings[slot.key]} ariaLabel={slot.label || `${columnLabel(column)}${row + 1}`} options={options} searchText={searchText} placeholder={placeholder} disabled={disabled} onChange={(value) => onChange(slot.key, value)} />
              </div>}</td>;
            })}
          </tr>)}</tbody>
        </table>
      </div>
    </section>)}
  </div>;
}

export function TemplateBindingEditor({ fileName, slots, placeholders, fields, bindings, readOnly, onChange }: {
  fileName?: string;
  slots: TemplateSlot[];
  placeholders: string[];
  fields: TemplateField[];
  bindings: Record<string, string>;
  readOnly: boolean;
  onChange: (next: Record<string, string>) => void;
}) {
  const { t } = usePreferences();
  const { message } = App.useApp();
  const isExcel = fileName?.toLowerCase().endsWith(".xlsx") || slots.some((slot) => slot.key.startsWith("xlsx:"));
  const visibleFields = useMemo(() => fields.filter((field) => field.readable), [fields]);
  const fieldOptionData = useMemo(() => buildFieldOptions(visibleFields, t), [visibleFields, t]);
  const fieldPlaceholder = t("templateSelectField");

  const updateBinding = (target: string, fieldKey?: string) => {
    const next = { ...bindings };
    if (fieldKey) next[target] = fieldKey;
    else delete next[target];
    onChange(next);
  };

  const copyPlaceholder = async (name: string) => {
    try {
      await navigator.clipboard.writeText(`{{${name}}}`);
      void message.success(t("templatePlaceholderCopied"));
    } catch {
      void message.error(t("unavailable"));
    }
  };

  return <div className="template-binding-editor">
    <Card size="small" title={t("templatePlaceholders")}>
      {placeholders.length ? <div className="template-placeholder-list">
        {placeholders.map((name) => <Space key={name} className="template-placeholder-item" wrap>
          <Tag color="blue">{`{{${name}}}`}</Tag>
          <FieldSelect value={bindings[name]} ariaLabel={`${fieldPlaceholder}: {{${name}}}`} options={fieldOptionData.options} searchText={fieldOptionData.searchText} placeholder={fieldPlaceholder} disabled={readOnly} onChange={(value) => updateBinding(name, value)} />
          <Button type="text" icon={<CopyOutlined />} disabled={readOnly} aria-label={t("templateCopyPlaceholder", { name })} onClick={() => void copyPlaceholder(name)} />
        </Space>)}
      </div> : <Typography.Text type="secondary">{t("templateNoPlaceholders")}</Typography.Text>}
    </Card>

    <Card size="small" title={<Space wrap><span>{t("templateBindingSlots")}</span><Typography.Text type="secondary">{t(isExcel ? "templateExcelBindingHint" : "templateWordBindingHint")}</Typography.Text></Space>}>
      {slots.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t("templateNoSlots")} />
        : isExcel ? <ExcelSlots slots={slots} bindings={bindings} options={fieldOptionData.options} searchText={fieldOptionData.searchText} placeholder={fieldPlaceholder} disabled={readOnly} onChange={updateBinding} />
          : <div className="template-word-page">
            {slots.map((slot) => <WordSlotCard key={slot.key} slot={slot} field={bindings[slot.key]} options={fieldOptionData.options} searchText={fieldOptionData.searchText} placeholder={fieldPlaceholder} disabled={readOnly} onChange={(value) => updateBinding(slot.key, value)} />)}
          </div>}
    </Card>

    {visibleFields.length === 0 && <Typography.Text type="secondary">{t("templateNoReadableFields")}</Typography.Text>}
  </div>;
}
