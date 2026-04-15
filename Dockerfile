FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app

ENV GRADLE_USER_HOME=/app/.gradle
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1 -Dorg.gradle.jvmargs='-Xmx768m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8'"

COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN chmod +x gradlew
RUN ./gradlew --version --no-daemon

COPY src src
RUN ./gradlew installDist --no-daemon --max-workers=1 --stacktrace

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=builder /app/build/install/tg_bot /app

RUN mkdir -p /app/data && useradd --system --create-home --uid 10002 appuser && chown -R appuser:appuser /app

USER appuser

ENV TG_BOT_DATA_DIR=/app/data
ENV TG_BOT_API_BASE_URL=http://backend:8080

CMD ["/app/bin/tg_bot"]
