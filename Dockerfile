# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-17 AS build

ARG SERVICE_NAME
WORKDIR /workspace

RUN test -n "${SERVICE_NAME}" || (echo "SERVICE_NAME build arg is required" && exit 1)

COPY pom.xml ./
COPY service-registry/pom.xml service-registry/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY room-service/pom.xml room-service/pom.xml
COPY message-service/pom.xml message-service/pom.xml
COPY media-service/pom.xml media-service/pom.xml
COPY presence-service/pom.xml presence-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY websocket-service/pom.xml websocket-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY admin-server/pom.xml admin-server/pom.xml
COPY code-coverage-report/pom.xml code-coverage-report/pom.xml

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -DskipTests dependency:go-offline

COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -pl "${SERVICE_NAME}" -am package -DskipTests -Djacoco.skip.check=true

FROM eclipse-temurin:17-jre-alpine

ARG SERVICE_NAME
WORKDIR /app
RUN addgroup -S connecthub && adduser -S connecthub -G connecthub

COPY --from=build /workspace/${SERVICE_NAME}/target/*.jar /app/app.jar
RUN chown -R connecthub:connecthub /app

USER connecthub
EXPOSE 8080 8081 8082 8083 8084 8085 8086 8087 8088 8089 8761

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -jar /app/app.jar"]
