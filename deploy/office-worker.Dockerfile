FROM node:25-bookworm-slim AS node-runtime

FROM eclipse-temurin:25-jdk AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY --from=node-runtime /usr/local/bin/ /usr/local/bin/
COPY --from=node-runtime /usr/local/lib/node_modules/ /usr/local/lib/node_modules/
COPY . .
RUN node --version && ./backend/mvnw -f backend/pom.xml -B -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre
WORKDIR /app
# The Ubuntu 26.04 base includes Canonical Pebble, which this JVM service does not use.
RUN rm -f /usr/bin/pebble
COPY --from=build /workspace/backend/target/platform-server-0.1.0-SNAPSHOT.jar /app/app.jar
RUN mkdir -p /tmp/lumira-office && chown -R 10001:10001 /tmp/lumira-office
USER 10001:10001
ENV LUMIRA_OFFICE_WORKER_JAR=/app/app.jar
EXPOSE 18091
ENTRYPOINT ["java","-XX:ActiveProcessorCount=1","-XX:+UseSerialGC","-XX:CICompilerCount=2","-Xss512k","-Xms32m","-Xmx192m","-XX:MaxMetaspaceSize=160m","-XX:MaxDirectMemorySize=64m","-XX:ReservedCodeCacheSize=32m","-jar","/app/app.jar","--office-worker"]
