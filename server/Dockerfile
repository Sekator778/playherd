# Multi-stage build for the Herd Quarkus server (engine + bots + server), D-44/D-20.
#
# Build context MUST be the repo root (so this stage can see both engine/ and server/):
#   docker build -f server/Dockerfile -t herd-server .
#
# Inside Docker we deliberately use plain Maven Central via the image's default
# ~/.m2/settings.xml, NOT the host's ~/.m2/settings-oss.xml (task requirement) — the container
# has no access to that file anyway, and default Maven settings already resolve everything from
# Central for this project (no internal/employer repositories involved here).

# --- Stage 1: build engine + server with Maven ---
# `--platform=$BUILDPLATFORM`: the build always runs natively on the machine doing the build — the
# Java output (jars, the Quarkus fast-jar layout) does not depend on the CPU architecture, so a
# multi-platform build (release.yml: linux/amd64 + linux/arm64 on an amd64 runner) compiles once
# instead of running Maven for the other architecture under QEMU emulation. Only the runtime stage
# below is per target platform.
FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copy just the POMs first for better layer caching of dependency resolution.
COPY pom.xml pom.xml
COPY engine/pom.xml engine/pom.xml
COPY bots/pom.xml bots/pom.xml
COPY server/pom.xml server/pom.xml
RUN mvn -B -f pom.xml -pl engine,bots,server -am dependency:go-offline || true

COPY engine engine
COPY bots bots
COPY server server

# Build engine first (installs it into the build container's local repo), then package server
# (which depends on engine) into a runnable fast-jar under server/target/quarkus-app.
RUN mvn -B -f pom.xml -pl engine,bots,server -am -DskipTests install

# --- Stage 2: minimal runtime ---
FROM eclipse-temurin:21-jre-jammy AS runtime

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
WORKDIR /deployments

# Non-root runtime user, standard Quarkus container convention.
RUN useradd --system --create-home --shell /bin/bash herd
RUN chown herd:herd /deployments
# D-107: the event journal (a Chronicle Queue) lives here; docker-compose.yml mounts a named volume on it. The directory is made and owned
# by herd in the image so that a new, empty volume mounted on it is writable by the server.
RUN mkdir -p /var/lib/herd/journal && chown -R herd:herd /var/lib/herd
USER herd

COPY --chown=herd:herd --from=build /build/server/target/quarkus-app/lib/ /deployments/lib/
COPY --chown=herd:herd --from=build /build/server/target/quarkus-app/*.jar /deployments/
COPY --chown=herd:herd --from=build /build/server/target/quarkus-app/app/ /deployments/app/
COPY --chown=herd:herd --from=build /build/server/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8081

ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0" HERD_JOURNAL_DIR=/var/lib/herd/journal
# The first line of options is what Chronicle Queue needs on Java 17+ (https://chronicle.software/insights/blogs/running-chronicle-on-java-17-and-later-the-required-jvm-arguments-explained,
# read 2026-10-21; the list is in server/pom.xml as herd.chronicle.jvm-args); they are here and not in JDK_JAVA_OPTIONS because
# docker-compose.yml sets that variable for the heap. chronicle.analytics.disable: Chronicle's usage statistics to a third party stay off.
ENTRYPOINT ["java", \
  "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED", "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED", \
  "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", \
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED", \
  "--add-opens=java.base/java.util=ALL-UNNAMED", "-Dchronicle.analytics.disable=true", \
  "-Dquarkus.http.host=0.0.0.0", "-jar", "/deployments/quarkus-run.jar"]
