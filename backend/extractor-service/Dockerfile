# Build context is backend/ — compose sets:
#   build: { context: ./backend, dockerfile: extractor-service/Dockerfile }

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Reactor needs every module pom; poms-first keeps the dependency layer cached
COPY pom.xml .
COPY ats-common/pom.xml ats-common/
COPY api-service/pom.xml api-service/
COPY parser-service/pom.xml parser-service/
COPY extractor-service/pom.xml extractor-service/
RUN mvn -q -B -pl extractor-service -am dependency:go-offline
COPY ats-common/src ats-common/src
COPY extractor-service/src extractor-service/src
RUN mvn -q -B -pl extractor-service -am package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/extractor-service/target/*.jar app.jar
# Pure Kafka worker: no EXPOSE, no healthcheck target
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
