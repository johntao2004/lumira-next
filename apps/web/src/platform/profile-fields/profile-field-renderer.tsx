"use client";

import { Input, InputNumber, Select } from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { DictionarySelect } from "@/platform/components/dictionary-select";
import { usePreferences } from "@/platform/providers";
import { localizeProfileFieldGroupLabel, localizeProfileFieldLabel } from "./localization";
import { profileFieldRuns, type DictionarySelection, type ProfileFieldDefinition, type ProfileFieldOption } from "./types";
import { profileFieldValueValid } from "./validation";

type Props = {
  fields: readonly ProfileFieldDefinition[];
  disabled?: boolean;
  dictionarySelections?: Readonly<Record<string, DictionarySelection>>;
};

function optionsFor(field: ProfileFieldDefinition): ProfileFieldOption[] {
  return (field.options ?? []).map(option => typeof option === "string"
    ? { value: option, label: option }
    : option);
}

/**
 * Render only the already-authorized fields supplied by the host endpoint.
 * It accepts labels/options as data and has no route, HTML, or script hooks.
 */
export function ProfileFieldRenderer({ fields, disabled = false, dictionarySelections }: Props) {
  const { t } = usePreferences();
  const renderField = (field: ProfileFieldDefinition) => {
    const options = optionsFor(field);
    const selection = dictionarySelections?.[field.key];
    return <Form.Item
      key={field.key}
      name={field.key}
      label={localizeProfileFieldLabel(field, t)}
      extra={field.description || undefined}
      rules={[{ required: field.required, message: t("invalidProfileValues") }, { validator: async (_, value: unknown) => {
        if (!profileFieldValueValid(field, value)) throw new Error(t("invalidProfileValues"));
      } }]}
      getValueFromEvent={field.type === "SELECT" || field.type === "NUMBER" ? (value: string | number | undefined | null) => value ?? "" : undefined}
    >
      {field.type === "SELECT" && field.dictionaryCode
        ? <DictionarySelect
            code={field.dictionaryCode}
            disabled={disabled}
            currentSelection={selection ? {
              itemId: selection.itemId,
              label: selection.label,
              enabled: selection.enabled,
            } : undefined}
          />
        : field.type === "SELECT"
          ? <Select allowClear disabled={disabled} placeholder={field.placeholder || undefined} options={options} />
          : field.type === "TEXTAREA"
            ? <Input.TextArea disabled={disabled} rows={4} maxLength={2000} placeholder={field.placeholder || undefined} />
            : field.type === "NUMBER"
              ? <InputNumber stringMode disabled={disabled} maxLength={40} inputMode="decimal" placeholder={field.placeholder || undefined} />
              : <Input
                  disabled={disabled}
                  type={field.type === "DATE" ? "date" : "text"}
                  maxLength={field.type === "DATE" ? 10 : 200}
                  placeholder={field.placeholder || undefined}
                />}
    </Form.Item>;
  };

  return <>{profileFieldRuns(fields).map((run, index) => run.kind === "field"
    ? renderField(run.field)
    : <fieldset key={`${run.key}-${index}`} className="mb-4 border-0 p-0">
        <legend>{localizeProfileFieldGroupLabel({ groupKey: run.key, groupLabel: run.label, custom: run.fields.some(field => field.custom === true) }, t)}</legend>
        {run.fields.map(renderField)}
      </fieldset>)}</>;
}
