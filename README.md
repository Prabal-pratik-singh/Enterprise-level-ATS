# ATS Pipeline — event-driven resume screening, on one laptop

A local, zero-cost, fully working demo of an enterprise Applicant Tracking System —
the kind built to screen **500K applicants per job posting** — running entirely on
Docker Compose: event-driven pipeline on Kafka, CQRS-lite read models, hybrid
keyword+semantic search, LLM extraction & rerank, fraud detection, and explainable
scoring.

The architecture is the production architecture; scale is the only demo concession.
Swapping MinIO→S3, Compose→K8s+KEDA, and Ollama→Bedrock takes the same design to
500K — config, not redesign.

**Honest demo simplifications:** no auth (single demo recruiter), no ClamAV
(magic-byte validation instead), no Debezium (simple polling outbox), no
field-level encryption, no Kubernetes.

> Build status by phase: see [PROGRESS.md](PROGRESS.md).

## Run it

```bash
docker compose up -d --build
```

| Service | Where |
|---|---|
| API | http://localhost:8080 — health at `/actuator/health` |
| Kafka UI (lag visualizer) | http://localhost:8085 |
| MinIO console | http://localhost:9001 (minioadmin / minioadmin) |
| Postgres (pgvector) | localhost:5432 (ats / ats) |
| Frontend (from Phase 5) | http://localhost:5173 |
| Ollama (from Phase 4, profile `llm`) | http://localhost:11434 |
| OpenSearch (from Phase 7, profile `search`) | http://localhost:9200 |

No `.env` needed — compose ships working defaults; copy `.env.example` to `.env`
to override.

## How it's put together

**One Spring Boot image plays every role.** The `APP_ROLE` env var (`api`,
`parser`, `extractor`, `embedder`, `dedup`, `fraud`, `matcher`, `indexer`) decides
which Kafka listeners a container activates and whether it serves HTTP. Compose
runs the same image as multiple services — which is what makes
`docker compose up -d --scale parser=8` possible with zero extra images.

**Events over Kafka** (KRaft, single broker): 9 domain topics + a `.dlq` twin
each, 12 partitions per topic, keyed by `applicationId`, payloads carry S3 keys
and ids — never file bytes. State-changing writes go through a transactional
outbox relayed to Kafka, so a crash can't lose an event.

**Files in MinIO** behind the AWS SDK v2 — "S3" is an env var, uploads are
presigned PUTs so resume bytes never transit the API.

## Phase log

### Phase 1 — Infrastructure & skeleton
- Compose stack: Kafka (KRaft, dual listeners) + Kafka UI, Postgres with pgvector,
  MinIO with one-shot bucket init — healthchecks and memory limits on everything;
  OpenSearch and Ollama declared behind `search`/`llm` profiles for later phases.
- Backend skeleton: Spring Boot 3.5 / Java 21, `APP_ROLE` mechanism, actuator
  health, KafkaAdmin pre-creating all 18 topics (9 domain + 9 DLQ) at 12
  partitions, AWS SDK v2 S3 client wired to MinIO, Flyway `V1` schema — 9 tables
  including `candidate_profiles.embedding vector(768)`, the `processed_events`
  idempotency ledger, and the `outbox`.
- Docker image: multi-stage build, JRE 21 runtime with tesseract-ocr (+eng) baked
  in for the Phase 3 OCR fallback.
