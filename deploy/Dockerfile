FROM node:24-bookworm-slim AS frontend
WORKDIR /src
COPY nanda-frontend/package*.json ./
RUN npm ci
COPY nanda-frontend/ ./
RUN npm test && npm run build

FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY Swarmwatch-svc/ ./
COPY --from=frontend /src/dist/ ./src/main/resources/static/
RUN chmod +x gradlew && ./gradlew --no-daemon test bootJar

FROM eclipse-temurin:25-jre-noble AS jre
FROM mcr.microsoft.com/playwright/java:v1.64.0-noble
COPY --from=jre /opt/java/openjdk/ /opt/java/openjdk/
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
COPY --from=build --chown=pwuser:pwuser /src/build/libs/Swarmwatch-svc-0.0.1-SNAPSHOT.jar /app/app.jar
USER pwuser
EXPOSE 8080
ENTRYPOINT ["/opt/java/openjdk/bin/java", "-jar", "/app/app.jar"]
