# Build context is backend/ (the Maven reactor root) — compose sets:
#   build: { context: ./backend, dockerfile: parser-service/Dockerfile }

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# The reactor needs EVERY module's pom to resolve the build graph, even for
# modules we don't compile here. Poms first = dependency layer stays cached
# until a pom actually changes.
COPY pom.xml .
COPY ats-common/pom.xml ats-common/
COPY api-service/pom.xml api-service/
COPY parser-service/pom.xml parser-service/
RUN mvn -q -B -pl parser-service -am dependency:go-offline
# Only the sources we actually build: the shared library + this service
COPY ats-common/src ats-common/src
COPY parser-service/src parser-service/src
# -pl parser-service = build this module; -am = "also make" what it depends on (ats-common)
RUN mvn -q -B -pl parser-service -am package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
# tesseract-ocr + -eng: the OCR engine and its English language pack.
# libtesseract-dev: provides the plain libtesseract.so name that tess4j's JNA
# bridge loads (the runtime package only ships a versioned .so.5).
# No TESSDATA_PREFIX env needed — OcrEngine probes the standard locations.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng libtesseract-dev \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/parser-service/target/*.jar app.jar
# No EXPOSE: pure Kafka worker, nothing connects TO it
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
