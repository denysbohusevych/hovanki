# Runtime-only image of the game server. The jar is built by Gradle first, not inside Docker:
#   ./gradlew :server:bootJar
#   docker build -t hovanki-server server
#   docker run --rm -p 8080:8080 hovanki-server
# CI does the same in .github/workflows/server-image.yml. The performance lab (perf/compose.yaml, docs/perf.md) builds
# it on the JDK instead, for jcmd and the profiler: the same JVM, the same everything else.
ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}

# A fixed uid and gid: the TLS key on the VM belongs to them (deploy/hovanki-cert-deploy.sh, docs/deploy.md).
RUN groupadd --system --gid 10001 hovanki \
    && useradd --system --uid 10001 --gid hovanki --home-dir /app --shell /usr/sbin/nologin hovanki

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
# G1 by name: with under 2 CPUs or 1792 MB the JVM picks Serial itself, and Serial cleans the old generation only by a
# full collection that stops the server for 300–500 ms once the heap fills. G1 cleans it in parts: on the live channel
# v2's UDP at 890 MB it holds 18 000 players against Serial's 12 000, on the socket with its own TLS 2 400 against
# 1 800, for 10–25 % more CPU a sync and ~35 MB more outside the heap (perf/results/2026-10-16-gc.md).
# A pause goal of 20 ms (G1's own is 200): G1 keeps the young generation as small as its collection fits in 20 ms. With
# the default the young pauses over UDP reach 35–56 ms and the datagrams queue behind them; with the goal they stay
# under ~30 ms, and at 18 000 players a sync's p95/p99 go from 14/32 to 8/20 ms (perf/results/2026-10-17-g1-pause.md).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:+UseG1GC -XX:MaxGCPauseMillis=20"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
