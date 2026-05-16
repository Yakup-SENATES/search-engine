# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# Search Engine Aggregator Service — multi-stage Dockerfile
#
# Requirements: REQ 17.1, REQ 17.4
# Design ref:   design.md § Docker → Multi-stage Dockerfile
# ---------------------------------------------------------------------------

# ---- build stage ---------------------------------------------------------
# Pin Maven 3.9 on Eclipse Temurin 21 to match the project's Java target
# (pom.xml: <java.version>21</java.version>).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Warm the dependency cache first so subsequent source-only changes do not
# invalidate the (large) downloaded-dependencies layer.
COPY pom.xml .
COPY .mvn .mvn
RUN mvn -B -ntp -q dependency:go-offline

# Now copy the source and build the executable jar.
COPY src src
RUN mvn -B -ntp -q -DskipTests package

# ---- runtime stage -------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# `curl` is required for the HEALTHCHECK below; the slim JRE image does not
# ship it. Install it via apt and clean up apt lists to keep the layer small.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Copy the fat jar produced by the Spring Boot Maven plugin. The pattern
# `searchengine-*.jar` matches the executable jar (e.g.
# `searchengine-0.0.1-SNAPSHOT.jar`) without picking up the
# `.jar.original` archive Spring Boot leaves alongside it.
COPY --from=build /workspace/target/searchengine-*.jar /app/app.jar

EXPOSE 8080

# REQ 17.4: container reports unhealthy if /actuator/health is unreachable.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD curl -fsS http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
