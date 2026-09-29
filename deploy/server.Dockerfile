FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# The Maven build generates the built-in localization catalog from the web dictionary,
# so it needs the Node runtime, the generator script and that single source file.
COPY --from=node:24-bookworm-slim /usr/local/bin/node /usr/local/bin/node
COPY scripts/generate-localization-catalog.mjs scripts/generate-localization-catalog.mjs
COPY apps/web/src/platform/i18n/messages.ts apps/web/src/platform/i18n/messages.ts
COPY backend backend
RUN ./backend/mvnw -f backend/pom.xml -B -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/backend/target/platform-server-0.1.0-SNAPSHOT.jar app.jar
# The legacy local blob store (kept only to migrate old files into PostgreSQL) needs a writable
# root; the application directory is root-owned, so point it at a dedicated data directory.
RUN mkdir -p /data/files && chown 10001:10001 /data/files
USER 10001:10001
ENV LUMIRA_BIND=0.0.0.0 LUMIRA_FILES_LOCAL_ROOT=/data/files
EXPOSE 8090
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
