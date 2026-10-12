# Образ собирается из исходников самого сервиса: монорепо больше нет.
# Контракты платформы лежат в GitHub Packages, читать их можно только с токеном. Токен приходит
# секретом BuildKit в виде готового settings.xml и не попадает ни в один слой образа:
#   docker build --secret id=maven_settings,src=$HOME/.m2/settings.xml -t app .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    ./mvnw -B --no-transfer-progress -q dependency:go-offline -DexcludeGroupIds=com.dima

COPY src/ src/
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    ./mvnw -B --no-transfer-progress -q package -DskipTests \
    && cp target/*-exec.jar /build/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /build/app.jar app.jar
USER app
EXPOSE 8080

# Форма exec: Java становится PID 1 и получает SIGTERM напрямую, без оболочки между ними.
# Куча — 60% лимита контейнера (mem_limit в docker-compose платформы): остальное нужно JVM
# вне кучи, иначе под нагрузкой сервис уходит за лимит и получает OOM-kill без следов в логе.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "app.jar"]
