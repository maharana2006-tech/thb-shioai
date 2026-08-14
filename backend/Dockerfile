# Sprint 51 BP-M3 — multi-stage backend image.
#
# Stage 1 (build): full JDK + Maven so we can package the fat jar in-container.
#   Uses the eclipse-temurin JDK 21 image (matches pom java.version=21 bumped
#   in BP-L3). Copies pom first so a source-only edit reuses the dependency
#   cache. Runs mvnw package -DskipTests — CI runs the tests separately, this
#   image just needs the artifact.
#
# Stage 2 (runtime): JRE only, non-root user. Copy just the jar and go. Actuator
#   /health endpoint (added in BP-M4) drives the HEALTHCHECK below.

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace/backend

# Cache dependency layer separately from source. mvnw + wrapper + pom copy in
# first; go-offline downloads every declared dep into ~/.m2 so a source-only
# change on the next build reuses the cached layer instead of re-downloading.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true

COPY src ./src
COPY scripts ./scripts
RUN ./mvnw package -DskipTests -B \
    && mv target/*.jar target/app.jar

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime

# curl for the HEALTHCHECK probe. Alpine's apk install is one-shot; no cache
# left behind so the image stays small.
RUN apk add --no-cache curl

# Non-root user id 65532 matches distroless / GCP convention. UID/GID are
# unnamed so K8s SecurityContext runAsNonRoot passes without a lookup.
RUN addgroup -S -g 65532 app && adduser -S -u 65532 -G app app

WORKDIR /app
COPY --from=build --chown=65532:65532 /workspace/backend/target/app.jar /app/app.jar

USER 65532:65532
EXPOSE 8080

# HEALTHCHECK hits the Actuator liveness probe (added in BP-M4). Distinct
# from readiness — this reflects "the JVM is alive and can serve requests",
# not "we are ready to receive traffic".
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

# Container defaults to matching JVM heap to container memory limits.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
