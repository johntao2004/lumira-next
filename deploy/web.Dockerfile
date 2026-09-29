FROM node:24-bookworm-slim AS build
WORKDIR /workspace
RUN corepack enable
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY apps/web/package.json apps/web/package.json
RUN corepack pnpm install --frozen-lockfile
COPY apps/web apps/web
RUN corepack pnpm build
COPY scripts/generate-csp.mjs scripts/generate-csp.mjs
RUN node scripts/generate-csp.mjs apps/web/dist/index.html /workspace/csp.caddy
FROM caddy:2-alpine
COPY deploy/web.Caddyfile /etc/caddy/Caddyfile
COPY --from=build /workspace/csp.caddy /etc/caddy/csp.caddy
COPY --from=build /workspace/apps/web/dist /srv
EXPOSE 8010
