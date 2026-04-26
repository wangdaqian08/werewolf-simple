# syntax=docker/dockerfile:1.7

# ---- Stage 1: build the fat jar ------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# Gradle wrapper + build scripts first — maximises Docker layer cache hits.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# --mount=type=cache persists the Gradle dependency cache across image
# builds on the same host. Without this, every `docker compose up --build`
# re-downloads ~300 MB from Maven Central and the Gradle distribution, which
# on a 2-vCPU VM pushes the backend build to 20+ minutes. With the cache,
# subsequent rebuilds are ~3–5 min.
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --version --no-daemon

# Now copy the actual source tree.
COPY src ./src

# Build the executable Spring Boot jar. --no-daemon keeps memory predictable
# inside the container. Tests run in CI, not at image-build time.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -x test

# ---- Stage 2: runtime ----------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# curl for the docker-compose healthcheck (busybox wget would also work).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy the single fat jar produced by bootJar.
COPY --from=build /src/build/libs/*.jar /app/app.jar

EXPOSE 8080

# JVM flags tuned for a 1 GB GCP free-tier VM; override with JAVA_OPTS if needed.
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
