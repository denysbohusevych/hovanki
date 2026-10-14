# Runtime-only image of the game server. The jar is built by Gradle first, not inside Docker:
#   ./gradlew :server:bootJar
#   docker build -t hovanki-server server
#   docker run --rm -p 8080:8080 hovanki-server
# CI does the same in .github/workflows/server-image.yml. The performance lab (perf/compose.yaml, docs/perf.md) builds
# it on the JDK instead, for jcmd and the profiler: the same JVM, the same everything else.
ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}

RUN groupadd --system hovanki \
    && useradd --system --gid hovanki --home-dir /app --shell /usr/sbin/nologin hovanki

WORKDIR /app
COPY build/libs/hovanki-server.jar /app/app.jar

USER hovanki
EXPOSE 8080
# The commit the image was built from: the admin shows it (docs/adr/0008-admin.md).
ARG REVISION=dev
ENV HOVANKI_REVISION=$REVISION
# Size the heap from the container memory limit, not from the host RAM: 55 % of it. The JVM's memory outside the heap
# (code, classes, threads, malloc) takes ~230 MB with 1 800 sockets; with 75 % the 640 MB container was killed at
# ~2 400 players, with 55 % the server slows down there instead (perf/results/2026-10-14-heap-share.md).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
