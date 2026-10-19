FROM node:24-bookworm-slim AS node-runtime

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY --from=node-runtime /usr/local/bin/ /usr/local/bin/
COPY --from=node-runtime /usr/local/lib/node_modules/ /usr/local/lib/node_modules/
RUN corepack enable pnpm && corepack prepare pnpm@10.33.0 --activate
COPY . .
RUN pnpm install --frozen-lockfile
RUN apt-get update \
    && apt-get install --yes --no-install-recommends python3 unzip zip \
    && rm -rf /var/lib/apt/lists/*
RUN ./backend/mvnw -f pom.xml -pl backend -am -B -DskipTests package
RUN pnpm plugins:build --no-host
RUN node scripts/plugin-runtime-lock.mjs --bundle backend/target/plugin-runtime/bundle

FROM eclipse-temurin:25-jre
WORKDIR /app
# The Ubuntu 26.04 base includes Canonical Pebble, which this JVM service does not use.
RUN rm -f /usr/bin/pebble
RUN apt-get update \
    && apt-get install --yes --no-install-recommends fontconfig fonts-wqy-zenhei \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/backend/target/platform-server-0.1.0-SNAPSHOT.jar app.jar
# Plugin bundles are part of the immutable release image; only extracted runtime state is writable.
COPY --from=build /workspace/backend/target/plugin-runtime/bundle /app/plugins
# The legacy local blob store is retained only for migration. Plugin extraction uses a private,
# container-local directory and never shares a writable plugin package volume between nodes.
RUN mkdir -p /data/files /tmp/lumira/plugins \
    && chown -R 10001:10001 /data/files /tmp/lumira/plugins \
    && chmod -R a-w /app/plugins
USER 10001:10001
ENV LUMIRA_BIND=0.0.0.0 \
    LUMIRA_FILES_LOCAL_ROOT=/data/files \
    LUMIRA_PLUGINS_PATH=/tmp/lumira/plugins \
    LUMIRA_PLUGINS_APPROVED_GRAPH=/app/plugins/approved-graph.json \
    LUMIRA_PLUGINS_FIXED_GRAPH=true
EXPOSE 8090
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
