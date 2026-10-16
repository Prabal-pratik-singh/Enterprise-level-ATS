# Build context is backend/ — compose sets:
#   build: { context: ./backend, dockerfile: fraud-service/Dockerfile }

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY ats-common/pom.xml ats-common/
COPY api-service/pom.xml api-service/
COPY parser-service/pom.xml parser-service/
COPY extractor-service/pom.xml extractor-service/
COPY matcher-service/pom.xml matcher-service/
COPY fraud-service/pom.xml fraud-service/
COPY dedup-service/pom.xml dedup-service/
COPY embedder-service/pom.xml embedder-service/
COPY indexer-service/pom.xml indexer-service/
RUN mvn -q -B -pl fraud-service -am dependency:go-offline
COPY ats-common/src ats-common/src
COPY fraud-service/src fraud-service/src
RUN mvn -q -B -pl fraud-service -am package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/fraud-service/target/*.jar app.jar
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
