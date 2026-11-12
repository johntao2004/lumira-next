"use client";

import { useEffect, useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button, Flex, Input, Select, Spin, Tag, Typography, theme } from "antd";
import { useIntl } from "@ant-design/pro-components";
import { api } from "@/platform/api/client";
import type { PageResult } from "@/platform/api/pagination";
import { listRequestParams, type ListRequest } from "@/platform/api/pagination";
import { QueryFailure } from "@/platform/components/query-failure";
import { usePreferences } from "@/platform/providers";
import { AssignmentSearchField, AssignmentSearchLayout } from "./assignment-search-layout";

type RoleSuggestion = { id: string; code: string; name: string };
type SelectedRole = RoleSuggestion;

/** Role assignment search with a server-backed name picker and a read-only role code. */
export function RoleAssignmentSearch({ userId, request, onChange, codeLabel, nameLabel, statusLabel }: {
  userId: string;
  request: ListRequest;
  onChange: (request: ListRequest) => void;
  codeLabel: string;
  nameLabel: string;
  statusLabel: string;
}) {
  const { t } = usePreferences();
  const intl = useIntl();
  const { token } = theme.useToken();
  const nameInputId = useId();
  const codeInputId = useId();
  const statusInputId = useId();
  const suggestionListId = useId();
  const [name, setName] = useState(request.filters?.name ?? "");
  const [code, setCode] = useState(request.filters?.code ?? "");
  const [enabled, setEnabled] = useState(request.filters?.enabled);
  const [selectedRole, setSelectedRole] = useState<SelectedRole | null>(null);
  const [debouncedName, setDebouncedName] = useState("");
  const [activeSuggestion, setActiveSuggestion] = useState(-1);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedName(name.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [name]);

  useEffect(() => {
    setName(request.filters?.name ?? "");
    setCode(request.filters?.code ?? "");
    setEnabled(request.filters?.enabled);
    setSelectedRole(current => current?.code === request.filters?.code && current?.name === request.filters?.name ? current : null);
    setActiveSuggestion(-1);
  }, [request.filters?.code, request.filters?.enabled, request.filters?.name]);

  const currentName = name.trim();
  const shouldLookup = currentName.length > 0 && currentName === debouncedName && !selectedRole;
  const suggestions = useQuery({
    queryKey: ["role-assignment-name-suggestions", userId, debouncedName],
    enabled: shouldLookup,
    queryFn: ({ signal }) => {
      const params = listRequestParams({
        page: 1,
        pageSize: 8,
        search: "",
        filters: { name: debouncedName },
        filterModes: { name: "contains" },
      });
      return api<PageResult<RoleSuggestion>>(`/platform/users/${userId}/role-options?${params}`, { signal });
    },
  });

  const search = () => {
    const filters: Record<string, string> = {};
    const filterModes: NonNullable<ListRequest["filterModes"]> = {};
    const normalizedName = name.trim();
    if (code) {
      filters.code = code;
      filterModes.code = "exact";
    }
    if (normalizedName) {
      filters.name = normalizedName;
      filterModes.name = "contains";
    }
    if (enabled) {
      filters.enabled = enabled;
      filterModes.enabled = "exact";
    }
    onChange({ ...request, page: 1, search: "", filters, filterModes });
  };

  const reset = () => {
    setName("");
    setCode("");
    setEnabled(undefined);
    setSelectedRole(null);
    setActiveSuggestion(-1);
    onChange({ ...request, page: 1, search: "", filters: {}, filterModes: {} });
  };

  const searchOnEnter = (event: React.KeyboardEvent<HTMLInputElement>) => {
    const optionsReady = shouldLookup && !suggestions.isFetching && !suggestions.error;
    if (event.key === "ArrowDown" && optionsReady && suggestions.data?.items.length) {
      event.preventDefault();
      setActiveSuggestion(index => Math.min(index + 1, suggestions.data!.items.length - 1));
      return;
    }
    if (event.key === "ArrowUp" && optionsReady && suggestions.data?.items.length) {
      event.preventDefault();
      setActiveSuggestion(index => Math.max(index - 1, -1));
      return;
    }
    if (event.key !== "Enter") return;
    event.preventDefault();
    event.stopPropagation();
    const role = optionsReady && activeSuggestion >= 0 ? suggestions.data?.items[activeSuggestion] : undefined;
    if (role) {
      setSelectedRole(role);
      setName(role.name);
      setCode(role.code);
      setActiveSuggestion(-1);
    } else {
      search();
    }
  };
  const activeOptionVisible = shouldLookup && !suggestions.isFetching && !suggestions.error
    && activeSuggestion >= 0 && activeSuggestion < (suggestions.data?.items.length ?? 0);

  return <AssignmentSearchLayout
    hint={<Typography.Paragraph type="secondary" style={{ margin: 0 }}>{t("roleNameLookupHint")}</Typography.Paragraph>}
    actions={<>
      <Button htmlType="button" onClick={reset}>{intl.getMessage("tableForm.reset", "重置")}</Button>
      <Button type="primary" htmlType="button" onClick={search}>{intl.getMessage("tableForm.search", "搜索")}</Button>
    </>}
  >
      <AssignmentSearchField label={nameLabel} inputId={nameInputId}>
        <Input
          id={nameInputId}
          value={name}
          maxLength={200}
          allowClear
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={shouldLookup}
          aria-controls={suggestionListId}
          aria-activedescendant={activeOptionVisible ? `${suggestionListId}-option-${activeSuggestion}` : undefined}
          onChange={event => {
            setName(event.target.value);
            setSelectedRole(null);
            setCode("");
            setActiveSuggestion(-1);
          }}
          onKeyDown={searchOnEnter}
        />
        {!selectedRole && currentName.length > 0 && currentName === debouncedName && <div id={suggestionListId} role="listbox" aria-label={nameLabel} style={{ marginTop: 4, maxHeight: 240, overflowY: "auto", border: `1px solid ${token.colorBorderSecondary}`, borderRadius: token.borderRadius, background: token.colorBgContainer }}>
          {suggestions.error
            ? <div style={{ padding: 8 }}><QueryFailure title={t("unavailable")} pending={suggestions.isFetching} onRetry={() => void suggestions.refetch()} /></div>
            : suggestions.isFetching
              ? <div style={{ padding: 12, textAlign: "center" }}><Spin size="small" /></div>
              : suggestions.data?.items.length
                ? suggestions.data.items.map((role, index) => <Button
                  key={role.id}
                  id={`${suggestionListId}-option-${index}`}
                  type="text"
                  block
                  role="option"
                  aria-selected={activeSuggestion === index}
                  onMouseDown={event => event.preventDefault()}
                  onMouseMove={() => setActiveSuggestion(index)}
                  onClick={() => {
                    setSelectedRole(role);
                    setName(role.name);
                    setCode(role.code);
                    setActiveSuggestion(-1);
                  }}
                  style={{ height: "auto", display: "block", justifyContent: "flex-start", padding: 12, textAlign: "left", background: activeSuggestion === index ? token.colorFillTertiary : undefined }}
                >
                  <Flex justify="space-between" align="center" gap={12} style={{ width: "100%" }}>
                    <Typography.Text ellipsis style={{ minWidth: 0, flex: 1 }}>{role.name}</Typography.Text>
                    <Tag title={role.code} style={{ maxWidth: "45%", flexShrink: 0, marginInlineEnd: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{role.code}</Tag>
                  </Flex>
                </Button>)
                : <Typography.Text type="secondary" style={{ display: "block", padding: 12 }}>{t("roleNameNoMatches")}</Typography.Text>}
        </div>}
      </AssignmentSearchField>
      <AssignmentSearchField label={codeLabel} inputId={codeInputId}>
        <Input id={codeInputId} value={code} readOnly />
      </AssignmentSearchField>
      <AssignmentSearchField label={statusLabel} inputId={statusInputId}>
        <Select
          id={statusInputId}
          aria-labelledby={`${statusInputId}-label`}
          allowClear
          style={{ width: "100%" }}
          value={enabled}
          onChange={setEnabled}
          options={[
            { value: "true", label: t("enabled") },
            { value: "false", label: t("disabled") },
          ]}
        />
      </AssignmentSearchField>
  </AssignmentSearchLayout>;
}
