"use client";

import { PlatformForm as Form } from "@/platform/components/platform-form";
import { SecurityEventsDrawer } from "./security-events-drawer";
import { EmailDrawer } from "./email-drawer";
import { PhoneDrawer } from "./phone-drawer";
import { ProfileCompletion } from "./profile-completion";
import { AvatarEditor } from "./avatar-editor";
import { PasskeysDrawer } from "./passkeys-drawer";
import { AnchorTabs as Tabs } from "@/platform/components/anchor-tabs";
import { ProfileFieldRenderer } from "@/platform/profile-fields/profile-field-renderer";
import type { DictionarySelection, ProfileFieldDefinition, ProfilePlacement } from "@/platform/profile-fields/types";
import { preserveProfileFieldValues } from "@/platform/profile-fields/values";
import { Alert, Button, Empty, Flex, Input, Spin } from "antd";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, mutate } from "@/platform/api/client";
import { useSubmitAction } from "@/platform/forms/use-submit-action";
import { FormDrawer } from "@/platform/components/form-drawer";
import { usePreferences } from "@/platform/providers";
import { FeedbackBubble } from "@/platform/components/feedback-bubble";

type BuiltInField = ProfileFieldDefinition & {
  key: "displayName" | "realName" | "birthDate" | "region";
  groupKey: string;
  groupLabel: string;
  placements?: readonly ProfilePlacement[];
};

type Profile = {
  fields: BuiltInField[];
  schemaVersion: number;
  username: string;
  displayName: string;
  realName: string;
  birthMonth: string;
  region: string;
  version: number;
};

type ProfileValues = {
  version: number;
  schemaVersion: number;
  fields: ProfileFieldDefinition[];
  values: Record<string, string>;
  dictionarySelections?: Record<string, DictionarySelection>;
};

const builtInLabelKeys = { displayName: "name", realName: "realName", birthDate: "birthMonth", region: "region" } as const;
const builtInDefaultLabels = { displayName: "Display name", realName: "Real name", birthDate: "Birth date", region: "Region" } as const;

function placementTab(field: { key: string; custom?: boolean; placements?: readonly ProfilePlacement[] }) {
  const placement = field.placements?.find(item => item.page === "account-profile");
  if (placement) return placement.tab;
  if (field.custom) return "custom";
  return field.key === "displayName" || field.key === "realName" || field.key === "birthDate" || field.key === "region" ? "basic" : undefined;
}

function BuiltInProfileFields({ fields, t }: { fields: BuiltInField[]; t: (key: string) => string }) {
  const content: React.ReactNode[] = [];
  let index = 0;
  while (index < fields.length) {
    const field = fields[index];
    const groupFields = [field];
    index += 1;
    while (index < fields.length && fields[index].groupKey === field.groupKey && fields[index].groupLabel === field.groupLabel) {
      groupFields.push(fields[index]);
      index += 1;
    }
    content.push(<fieldset key={`${field.groupKey}-${index}`} className="mb-4 border-0 p-0">
      <legend>{field.groupLabel}</legend>
      {groupFields.map(item => {
        const name = item.key === "birthDate" ? "birthMonth" : item.key;
        const labelKey = builtInLabelKeys[item.key];
        const label = item.label && item.label !== builtInDefaultLabels[item.key] ? item.label : t(labelKey);
        return <Form.Item
          key={item.key}
          name={name}
          label={label}
          extra={item.description || undefined}
          rules={[{ required: item.required, whitespace: true, message: t("invalidProfile") }, { max: 200, message: t("nameTooLong") }]}
        ><Input type={item.key === "birthDate" ? "month" : "text"} maxLength={item.key === "birthDate" ? 7 : 200} placeholder={item.placeholder || undefined} /></Form.Item>;
      })}
    </fieldset>);
  }
  return <>{content}</>;
}

export function ProfileDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [securityOpen, setSecurityOpen] = useState(false);
  const [emailOpen, setEmailOpen] = useState(false);
  const [phoneOpen, setPhoneOpen] = useState(false);
  const [passkeyOpen, setPasskeyOpen] = useState(false);
  const { t } = usePreferences();
  const client = useQueryClient();
  const { pending, error, setError, run } = useSubmitAction({ errorKeys: ["staleRecord", "invalidProfile", "invalidProfileValues"] });
  const query = useQuery({
    queryKey: ["profile"],
    queryFn: ({ signal }) => api<Profile>("/account/profile", { signal }),
    enabled: open,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
  const valuesQuery = useQuery({
    queryKey: ["profile-values"],
    queryFn: ({ signal }) => api<ProfileValues>("/account/profile-values", { signal }),
    enabled: open,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
  const profile = query.data;
  const profileValues = valuesQuery.data;
  const ready = !!profile && !!profileValues && !query.isFetching && !valuesQuery.isFetching && !query.error && !valuesQuery.error;
  const initialValues = ready ? { ...profile, ...profileValues.values } : undefined;
  const builtInsFor = (tab: "basic" | "details") => profile?.fields.filter(field => placementTab(field) === tab) ?? [];
  const customFor = (tab: "basic" | "details" | "custom") => profileValues?.fields.filter(field => placementTab(field) === tab) ?? [];

  return <>
    <SecurityEventsDrawer open={securityOpen} onClose={() => setSecurityOpen(false)} />
    <EmailDrawer open={emailOpen} onClose={() => setEmailOpen(false)} />
    <PhoneDrawer open={phoneOpen} onClose={() => setPhoneOpen(false)} />
    <PasskeysDrawer open={passkeyOpen} onClose={() => setPasskeyOpen(false)} />
    <FormDrawer
      title={t("profile")}
      open={open}
      formId="account-profile"
      pending={pending}
      submitDisabled={!ready}
      onClose={() => { setError(null); onClose(); }}
    >
      <ProfileCompletion open={open} />
      <Flex className="mb-4" gap="small" wrap>
        <Button disabled={pending} onClick={() => setSecurityOpen(true)}>{t("securityEvents")}</Button>
        <Button disabled={pending} onClick={() => setEmailOpen(true)}>{t("accountEmail")}</Button>
        <Button disabled={pending} onClick={() => setPhoneOpen(true)}>{t("accountPhone")}</Button>
        <Button disabled={pending} onClick={() => setPasskeyOpen(true)}>{t("passkeyCredentials")}</Button>
      </Flex>
      <FeedbackBubble id="account-profile-error" content={error ? t(error) : query.error || valuesQuery.error ? t("unavailable") : undefined} />
      {(query.isFetching || valuesQuery.isFetching) && <Spin />}
      {(query.error || valuesQuery.error) && <Alert
        className="mb-4"
        type="error"
        showIcon
        message={t("unavailable")}
        action={<Button size="small" onClick={() => { void query.refetch(); void valuesQuery.refetch(); }}>{t("retry")}</Button>}
      />}
      {ready && profile && profileValues && initialValues && <Form
        key={`${profile.version}-${profile.schemaVersion}-${profileValues.version}-${profileValues.schemaVersion}`}
        id="account-profile"
        layout="vertical"
        disabled={pending}
        initialValues={initialValues}
        onFinish={formValues => run(async () => {
          const profileValuesPayload = preserveProfileFieldValues(formValues, profileValues.fields, profileValues.values);
          await mutate("/account/profile", "PUT", {
            displayName: formValues.displayName ?? profile.displayName,
            realName: formValues.realName ?? profile.realName,
            birthMonth: formValues.birthMonth ?? profile.birthMonth,
            region: formValues.region ?? profile.region,
            version: profile.version,
            schemaVersion: profile.schemaVersion,
            profileValuesSchemaVersion: profileValues.schemaVersion,
            profileValues: profileValuesPayload,
          });
          onClose();
          await Promise.all([
            client.invalidateQueries({ queryKey: ["profile"] }),
            client.invalidateQueries({ queryKey: ["profile-values"] }),
            client.invalidateQueries({ queryKey: ["profile-completion"] }),
            client.invalidateQueries({ queryKey: ["session"] }),
            client.invalidateQueries({ queryKey: ["users"] }),
          ]);
        })}
      >
        <Form.Item label={t("username")}><Input value={profile.username} disabled /></Form.Item>
        <Tabs anchor="account-profile" items={[
          { key: "basic", label: t("userBasicInformation"), forceRender: true, children: <>
            <AvatarEditor />
            <BuiltInProfileFields fields={builtInsFor("basic")} t={t} />
            {customFor("basic").length > 0 && <ProfileFieldRenderer fields={customFor("basic")} disabled={pending} dictionarySelections={profileValues.dictionarySelections} />}
          </> },
          { key: "details", label: t("details"), forceRender: true, children: <>
            <BuiltInProfileFields fields={builtInsFor("details")} t={t} />
            {customFor("details").length > 0 && <ProfileFieldRenderer fields={customFor("details")} disabled={pending} dictionarySelections={profileValues.dictionarySelections} />}
            {builtInsFor("details").length === 0 && customFor("details").length === 0 && <Empty description={t("noCustomProfileFields")} />}
          </> },
          { key: "custom", label: t("fieldCustomGroup"), forceRender: true, children: customFor("custom").length > 0
            ? <ProfileFieldRenderer fields={customFor("custom")} disabled={pending} dictionarySelections={profileValues.dictionarySelections} />
            : <Empty description={t("noCustomProfileFields")} /> },
        ]} />
      </Form>}
    </FormDrawer>
  </>;
}
