FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY backend backend
RUN ./backend/mvnw -f backend/pom.xml -B -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/backend/target/platform-server-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
ENV LUMIRA_BIND=0.0.0.0
EXPOSE 8090
ENTRYPOINT ["java","-jar","/app/app.jar"]
