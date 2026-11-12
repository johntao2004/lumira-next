"use client";

import { SearchOutlined } from "@ant-design/icons";
import { ProFormText, QueryFilter } from "@ant-design/pro-components";
import { useQuery } from "@tanstack/react-query";
import { Alert, Button, Card, Empty, Skeleton } from "antd";
import { useEffect, useMemo, useRef, useState, type ComponentType } from "react";
import SwaggerUI from "swagger-ui";
import "swagger-ui/dist/swagger-ui.css";
import { api } from "@/platform/api/client";
import { useQueryFilterSpan } from "@/platform/components/use-query-filter-span";
import { usePreferences } from "@/platform/providers";
import { filterOpenApiDocument, readOnlyApiDocsConfig, type OpenApiDocument } from "./api-docs-spec";
import "./api-docs.css";

const READ_ONLY_SWAGGER_PLUGIN = () => ({
  components: {
    AuthorizeBtnContainer: () => null,
    authorizeBtn: () => null,
    authorizeOperationBtn: () => null,
    authorizationPopup: () => null,
    auths: () => null,
    TryItOutButton: () => null,
    onlineValidatorBadge: () => null,
  },
});

type SwaggerSystem = {
  getComponent: (name: string, container?: string) => ComponentType<Record<string, unknown>>;
  specActions: { updateSpec: (spec: string) => void };
};

function rejectApiRequest(): never {
  throw new Error("API documentation is read-only");
}

function ApiDocsSwagger({ spec }: { spec: OpenApiDocument }) {
  const [system, setSystem] = useState<SwaggerSystem | null>(null);
  const lastSpec = useRef(spec);

  useEffect(() => {
    setSystem(SwaggerUI({
      spec,
      ...readOnlyApiDocsConfig,
      requestInterceptor: rejectApiRequest,
      plugins: [READ_ONLY_SWAGGER_PLUGIN],
    }));
  }, []);

  useEffect(() => {
    if (!system || lastSpec.current === spec) return;
    system.specActions.updateSpec(JSON.stringify(spec));
    lastSpec.current = spec;
  }, [spec, system]);

  const SwaggerRoot = useMemo(() => system?.getComponent("App", "root"), [system]);
  return <div className="api-docs-swagger" aria-label="Swagger API reference">
    {SwaggerRoot ? <SwaggerRoot /> : <Skeleton active paragraph={{ rows: 7 }} />}
  </div>;
}

/** Read-only, authenticated OpenAPI reference served by the platform API. */
export function ApiDocsPage() {
  const { t } = usePreferences();
  const [appliedSearch, setAppliedSearch] = useState("");
  const queryContainerRef = useRef<HTMLDivElement>(null);
  const searchSpan = useQueryFilterSpan(queryContainerRef);
  const query = useQuery({
    queryKey: ["platform-api-docs"],
    queryFn: ({ signal }) => api<OpenApiDocument>("/platform/api-docs", { signal }),
    retry: false,
    refetchOnWindowFocus: false,
  });
  const filtered = useMemo(
    () => query.data ? filterOpenApiDocument(query.data, appliedSearch) : null,
    [query.data, appliedSearch],
  );

  return <div className="api-docs-page">
    <div className="api-docs-query-container" ref={queryContainerRef}>
      <Card className="api-docs-toolbar" variant="borderless" styles={{ body: { padding: 0 } }}>
        <QueryFilter
          className="api-docs-search resource-search"
          span={searchSpan}
          labelWidth="auto"
          defaultCollapsed
          onFinish={values => {
            setAppliedSearch(String(values.search ?? "").trim());
          }}
          onReset={() => setAppliedSearch("")}
        >
          <ProFormText
            name="search"
            label={t("apiDocsSearchLabel")}
            fieldProps={{
              allowClear: true,
              prefix: <SearchOutlined />,
              placeholder: t("apiDocsSearch"),
              "aria-label": t("apiDocsSearch"),
            }}
          />
        </QueryFilter>
      </Card>
    </div>

    {query.error && <Alert
      className="api-docs-error"
      type="error"
      showIcon
      message={t("apiDocsLoadFailed")}
      action={<Button size="small" loading={query.isFetching} onClick={() => void query.refetch()}>{t("retry")}</Button>}
    />}
    {query.isPending && <Card variant="borderless"><Skeleton active paragraph={{ rows: 5 }} /></Card>}
    {filtered && <Card className="api-docs-document" variant="borderless">
      {filtered.operationCount === 0
        ? <Empty description={t("apiDocsEmpty")} />
        : <ApiDocsSwagger spec={filtered.spec} />}
    </Card>}
  </div>;
}
