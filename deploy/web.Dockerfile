FROM node:24-bookworm-slim AS build
WORKDIR /workspace
RUN corepack enable
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY apps/web/package.json apps/web/package.json
COPY patches/ patches/
RUN corepack pnpm install --frozen-lockfile
COPY apps/web apps/web
COPY plugins/ plugins/
RUN ln -s ../apps/web/node_modules plugins/node_modules
RUN corepack pnpm build
COPY scripts/generate-csp.mjs scripts/generate-csp.mjs
RUN node scripts/generate-csp.mjs apps/web/dist/index.html /workspace/csp.caddy
FROM golang:1.26.9-alpine3.23 AS caddy-build
RUN apk add --no-cache git
RUN go install github.com/caddyserver/xcaddy/cmd/xcaddy@v0.4.5
# 修复 HTTP/2 SETTINGS 拒绝服务漏洞，并检查最终二进制确实采用补丁依赖。
# Fix the HTTP/2 SETTINGS denial of service and verify the patched dependency in the final binary.
# 官方校验服务可能瞬时失败；只做有界重试，不关闭模块校验或变更版本。
# Bound retries for transient upstream checksum-service failures without disabling verification or changing versions.
RUN set -eu; \
    for attempt in 1 2 3; do \
      rm -f /out/caddy; \
      if CGO_ENABLED=0 XCADDY_SKIP_CLEANUP=1 XCADDY_SETCAP=0 timeout 600s /go/bin/xcaddy build v2.11.7 --output /out/caddy \
          --replace golang.org/x/net=golang.org/x/net@v0.60.0; then break; fi; \
      if [ "$attempt" -eq 3 ]; then exit 1; fi; \
      sleep "$((attempt * 5))"; \
    done; \
    test -x /out/caddy; \
    go version -m /out/caddy | grep -F 'go1.26.9'; \
    go version -m /out/caddy | grep -E '=>[[:space:]]+golang.org/x/net[[:space:]]+v0.60.0'

FROM caddy:2.11.7-alpine AS patched-caddy
COPY --from=caddy-build /out/caddy /usr/bin/caddy

# 网关与静态站点共享已修补的二进制，各自保留配置职责。
# Gateway and static site share the patched binary while keeping separate configuration.
FROM patched-caddy AS edge
COPY deploy/Caddyfile /etc/caddy/Caddyfile
EXPOSE 8010

FROM patched-caddy AS web
COPY deploy/web.Caddyfile /etc/caddy/Caddyfile
COPY --from=build /workspace/csp.caddy /etc/caddy/csp.caddy
COPY --from=build /workspace/apps/web/dist /srv
EXPOSE 8010
