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
RUN CGO_ENABLED=0 XCADDY_SKIP_CLEANUP=1 XCADDY_SETCAP=0 /go/bin/xcaddy build v2.11.7 --output /out/caddy \
    && go version -m /out/caddy | grep -F 'go1.26.9'

FROM caddy:2.11.7-alpine
COPY --from=caddy-build /out/caddy /usr/bin/caddy
COPY deploy/web.Caddyfile /etc/caddy/Caddyfile
COPY --from=build /workspace/csp.caddy /etc/caddy/csp.caddy
COPY --from=build /workspace/apps/web/dist /srv
EXPOSE 8010
