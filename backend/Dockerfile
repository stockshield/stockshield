# StockShield 메인 서버 (Spring Boot 3.3.5, Java 17). 배포용 compose(infra/docker-compose.prod.yml)가 빌드한다.
# 1단계: Gradle로 실행 jar를 만든다(테스트는 CI에서 돌리므로 여기서는 뺀다)
FROM eclipse-temurin:17.0.12_7-jdk-jammy AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

# 2단계: JRE만 담은 실행 이미지
FROM eclipse-temurin:17.0.12_7-jre-jammy
ENV TZ=Asia/Seoul \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
WORKDIR /app
RUN useradd --system --no-create-home appuser
COPY --from=build /app.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
