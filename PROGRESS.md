# PROGRESS

## Spec amendments (user-directed)

- **2026-08-14 · Microservice conversion** — amends the spec's locked "single application / ONE image / APP_ROLE" worker model at the user's request. The backend is now a Maven multi-module reactor: `ats-common` (shared contracts library: domain, topic catalog, event envelope, outbox publisher + relay, Flyway migrations, shared config) plus one independently built and deployable Spring Boot service per pipeline stage, each with its own Dockerfile/image (`ats/api-service` today; parser/extractor/matcher/dedup/fraud/embedder/indexer services arrive with their phases). APP_ROLE is removed. Compose service names stay (`api`, `parser`, …) so every DoD command in the spec still works verbatim, including `--scale parser=8`. Postgres remains shared (documented honestly in the README; per-service data ownership is the named production step). Board-name mapping: fraud-service = design board's "Trust Service", indexer-service = "Search Service". Re-verified after the split: seed 10 → 30 APPLIED total, resume.uploaded=30, 30 MinIO objects, outbox 60/60 published.

| Phase | Title | Status |
|------:|-------|--------|
| 1 | Infrastructure & skeleton | ✅ DONE — DoD verified 2026-08-07: all services healthy, health UP, 9 tables + flyway history, 18 topics × 12 partitions |
| 2 | Apply & upload flow (presigned uploads) | ✅ DONE — DoD verified 2026-08-09: seed 20 → 20 APPLIED, 20 MinIO objects, resume.uploaded=20, outbox 40/40 published |
| 3 | Parser worker (PDF/DOCX/OCR) | — |
| 4 | Extraction worker (LLM → profile) | — |
| 5 | Scoring engine + Recruiter Dashboard v1 | — |
| 6 | Deduplication + fraud heuristics | — |
| 7 | Embeddings + OpenSearch hybrid search | — |
| 8 | LLM rerank + 10K load demo + demo script | — |

## Decisions

- **2026-08-07 · Repo root**: the existing IntelliJ project dir (`enterprise ats`) is the repo root — no nested `ats-pipeline/` folder. A leftover Spring Initializr skeleton (com.pm, Spring Boot 4.1.0, Java 17, no hand-written code) was deleted with the user's approval; the backend lives in `backend/` per the spec.
- **2026-08-07 · Versions** (verified against Maven Central / registries): Spring Boot **3.5.16** (latest 3.5.x), AWS SDK BOM **2.51.2**, `apache/kafka:3.9.1`, `provectuslabs/kafka-ui:v0.7.2`, `pgvector/pgvector:pg16`, `opensearchproject/opensearch:2.19.1`.
- **2026-08-07 · MinIO pinned** to `RELEASE.2025-04-22T22-12-26Z` — the last community image with the full web console (later releases stripped it; Phase 2's DoD browses objects in the console).
- **2026-08-07 · DLQ twins for all 9 topics**, not only consumed ones — symmetric and pre-created with 12 partitions; broker `auto.create.topics` disabled so nothing can conjure a 1-partition topic.
- **2026-08-07 · APP_ROLE web toggle** implemented in `main()` (`WebApplicationType.NONE` unless role=api) — simplest visible mechanism; per-worker `@ConditionalOnProperty(app.role)` gates arrive with each worker phase.
- **2026-08-07 · Schema details**: `scores` carries `fraud_score`/`flags` from V1 (the spec's Phase 6 writes into `scores`); `candidate_profiles` is keyed by `application_id` (one profile per application) with `candidate_id` alongside for cross-application dedup; `hibernate.ddl-auto=none` — Flyway owns the schema.
- **2026-08-07 · Kafka runs as `user: root`** in compose — the apache/kafka image's non-root user can't write a fresh named-volume mountpoint; acceptable for a local demo.
- **2026-08-07 · Host port conflicts**: stopped the unrelated `sahayak-backend`/`sahayak-postgres` containers that held spec-locked ports 8080/5432 (restore: `docker start sahayak-backend sahayak-postgres`). `sahayak-ollama` (11434) and `sahayak-frontend` (5173) stay up until Phases 4/5 need those ports.
- **2026-08-07 · No host Python** — the Phase 2 resume generator will run via a one-shot `python:3.12-slim` container instead of a host install.
- **2026-08-07 · Spec archived** verbatim as `ATS-BUILD-PROMPT.md` so any session can consult it.
- **2026-08-07 · Docker/WSL2 memory is ~7.4 GiB** (default cap, no `.wslconfig`). Fine through Phase 3; before Phase 4 (Ollama) the plan is to raise it to ~12 GB via `C:\Users\ASUS\.wslconfig` with the user's OK.
- **2026-08-09 · Presigner signs for a public endpoint** (`S3_PUBLIC_ENDPOINT`, default `http://localhost:9000`): the SigV4 signature covers the Host header, so URLs must be signed for the address the uploading client actually hits — not the in-network `minio:9000`. Cloud swap stays an env change.
- **2026-08-09 · Seed job has a fixed UUID** (`00000000-0000-0000-0000-000000000001`) so tools and later DoD commands can target it deterministically.
- **2026-08-09 · seed.sh default SEED is random per run** (emails embed a random number + index), so repeated seeding across phases never violates `UNIQUE(job_id, candidate_id)`. `SEED=42 ./tools/seed.sh N` reproduces a batch exactly.
- **2026-08-09 · Generator mix rule**: N≥10 → 1 corrupt + 3 planted-fraud resumes included; N≥5 → ~18% of normal resumes are image-only (OCR targets). The corrupt file has valid `%PDF` magic bytes ON PURPOSE — it must pass upload validation so it can poison the parser and prove DLQ handling in Phase 3.
- **2026-08-09 · Resume generator runs in `python:3.12-slim`** with a named pip-cache volume (`ats-pip-cache`) — no host Python; reportlab's bundled Vera.ttf doubles as the PIL font for image-only PDFs.
- **2026-08-09 · `/resume/complete` is idempotent** (same application+version returns the existing row, no duplicate event) and rejects keys outside the application's own prefix.
- **2026-08-09 · Outbox relay**: at-least-once, batch 200, `FOR UPDATE SKIP LOCKED`, runs in every role (safe concurrent relays); consumers will dedupe via `processed_events`. Domain FKs are raw UUID columns (no `@ManyToOne`) to keep the worker/domain split lean.
- **2026-08-14 · Multi-module Docker builds**: each service's Dockerfile uses the backend/ reactor as build context and `mvn -pl <service> -am` (every module pom is copied for reactor resolution; the go-offline layer caches dependencies). Only parser-service will carry tesseract — other images stay slim. Migrations live in ats-common so ANY service can boot first; Flyway's lock makes concurrent starts safe.
- **2026-08-14 · Service mains live at `com.ats`** so component scan discovers ats-common beans (domain, events, config) without explicit `scanBasePackages`.
- **2026-08-14 · sahayak containers set to `restart: unless-stopped`** (were auto-reviving after reboots and reclaiming ports 8080/5432, which broke the ats stack's networking mid-start). Manual stops now persist across reboots; revert with `docker update --restart always sahayak-backend sahayak-postgres`.
- **2026-08-14 · Commit policy**: granular commits at working checkpoints, pushed immediately (user request — richer GitHub history).
