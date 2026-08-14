# Build context is backend/ (the Maven reactor root) — compose sets:
#   build: { context: ./backend, dockerfile: api-service/Dockerfile }

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copy every module pom first: the reactor needs them all to resolve, and this
# layer caches the dependency download until a pom actually changes.
COPY pom.xml .
COPY ats-common/pom.xml ats-common/
COPY api-service/pom.xml api-service/
RUN mvn -q -B -pl api-service -am dependency:go-offline
COPY ats-common/src ats-common/src
COPY api-service/src api-service/src
RUN mvn -q -B -pl api-service -am package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
# curl: container healthcheck
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/api-service/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
