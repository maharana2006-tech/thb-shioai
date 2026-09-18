FROM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src
RUN gradle --no-daemon shadowJar -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S scanagent && adduser -S -G scanagent -H scanagent
WORKDIR /app
COPY --from=build /app/build/libs/multiship-lan-scanner-0.1.0.jar /app/app.jar
USER scanagent

# NOTE: mDNS requires the container to see the host's LAN interface.
# Run with `--network host` — Docker's default bridge does not forward
# multicast so JmDNS will find nothing otherwise.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
