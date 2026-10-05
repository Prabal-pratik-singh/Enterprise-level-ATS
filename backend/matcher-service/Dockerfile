# Build context is backend/ — compose sets:
#   build: { context: ./backend, dockerfile: matcher-service/Dockerfile }

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY ats-common/pom.xml ats-common/
COPY api-service/pom.xml api-service/
COPY parser-service/pom.xml parser-service/
COPY extractor-service/pom.xml extractor-service/
COPY matcher-service/pom.xml matcher-service/
RUN mvn -q -B -pl matcher-service -am dependency:go-offline
COPY ats-common/src ats-common/src
COPY matcher-service/src matcher-service/src
RUN mvn -q -B -pl matcher-service -am package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/matcher-service/target/*.jar app.jar
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
