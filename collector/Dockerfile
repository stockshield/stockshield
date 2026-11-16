# StockShield 시세 수집기 (Spring Boot 3.3.5, Java 17). 배포용 compose(infra/docker-compose.prod.yml)가 빌드한다.
FROM eclipse-temurin:17.0.12_7-jdk-jammy AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

FROM eclipse-temurin:17.0.12_7-jre-jammy
ENV TZ=Asia/Seoul \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
    COLLECTOR_DATA_DIR=/app/data
WORKDIR /app
RUN useradd --system --no-create-home appuser \
    && mkdir -p /app/data && chown appuser /app/data
COPY --from=build /app.jar app.jar
USER appuser
# 녹화 파일(ticks-YYYYMMDD.jsonl)은 볼륨으로 남긴다
VOLUME ["/app/data"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
