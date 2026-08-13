# Sprint 51 M-Ops (audit finding BP-M3) — first backend Dockerfile.
#
# Multi-stage build:
#   1. Builder stage — full JDK + Maven, resolves dependencies + compiles.
#      Keeps the runtime image lean (no Maven, no compiler, no test deps).
#   2. Runtime stage — JRE only, non-root user, HEALTHCHECK against the
#      Actuator health endpoint (M-Ops-c wires that in).
#
# Build:
#   docker build -t multiship-backend:local -f backend/Dockerfile backend
#
# Run:
#   docker run --rm -p 8080:8080 \
#     -e DB_URL=jdbc:postgresql://host.docker.internal:5432/multiship_db \
#     -e DB_USERNAME=postgres -e DB_PASSWORD=postgres \
#     -e JWT_SECRET=$(openssl rand -hex 32) \
#     multiship-backend:local

# ============================================================================
# STAGE 1: builder — resolve deps, compile, package as an executable JAR.
# ============================================================================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

# Cache the Maven wrapper + dependency resolution as an early layer so
# a code-only change doesn't force a full re-download on every build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

# Compile + package. Skip tests here — they run in CI upstream (unit
# job + integration-test job, PR #154). Rebuilding here would double the
# CI cost for no marginal safety.
COPY src ./src
RUN ./mvnw -B -ntp -DskipTests package \
    && cp target/backend-*.jar target/app.jar

# ============================================================================
# STAGE 2: runtime — JRE only, non-root, HEALTHCHECK.
# ============================================================================
FROM eclipse-temurin:21-jre-jammy

# curl is used by HEALTHCHECK below. wget would work too but curl is
# already the ecosystem standard for actuator probes.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root user. Fixed uid 65532 matches Google's distroless convention
# (so filesystem permissions remain compatible if the base image ever
# swaps to a distroless variant).
RUN groupadd --system --gid 65532 app \
    && useradd  --system --uid 65532 --gid app --home /app --shell /sbin/nologin app

WORKDIR /app
COPY --from=builder --chown=app:app /workspace/target/app.jar /app/app.jar

USER app

# HEALTHCHECK hits /actuator/health (wired in M-Ops-c). k8s liveness /
# readiness probes will override this from the pod spec, but for
# `docker run` or docker-compose the built-in check gives ops the same
# UP/DOWN signal without extra tooling.
#
# --start-period=45s lets Flyway + Hibernate init + Testcontainers-free
# boot complete before failure counts. Tune down once the JVM warm-up
# is characterised.
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# Bind to all interfaces inside the container; k8s / docker-compose
# publishes to the host network layer.
EXPOSE 8080

# JAVA_TOOL_OPTIONS is honoured by every JVM at start-up — a cleaner
# override surface than baking JVM flags into ENTRYPOINT. Ops can add
# heap sizing / GC flags via -e JAVA_TOOL_OPTIONS="..." without
# rebuilding the image. Sensible defaults: MaxRAMPercentage lets the
# JVM use up to 75% of the cgroup memory limit (safer than a fixed -Xmx).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# `exec` form (JSON array) so the JVM is PID 1 and SIGTERM propagates
# cleanly into Spring Boot's graceful-shutdown handler (M-Ops-b).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
