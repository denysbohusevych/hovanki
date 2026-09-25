# Runtime-only image of the game server. The jar is built by Gradle first, not inside Docker:
#   ./gradlew :server:bootJar
#   docker build -t hovanki-server server
#   docker run --rm -p 8080:8080 hovanki-server
# CI does the same in .github/workflows/release.yml.
FROM eclipse-temurin:21-jre

RUN groupadd --system hovanki \
    && useradd --system --gid hovanki --home-dir /app --shell /usr/sbin/nologin hovanki

WORKDIR /app
COPY build/libs/hovanki-server.jar /app/app.jar

USER hovanki
EXPOSE 8080
# Size the heap from the container memory limit, not from the host RAM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
