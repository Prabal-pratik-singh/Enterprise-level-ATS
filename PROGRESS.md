# PROGRESS

| Phase | Title | Status |
|------:|-------|--------|
| 1 | Infrastructure & skeleton | 🔨 IN PROGRESS — first `docker compose up -d --build` running, DoD checks pending |
| 2 | Apply & upload flow (presigned uploads) | — |
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
