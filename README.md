# ATS Pipeline — event-driven resume screening, on one laptop

A local, zero-cost, fully working demo of an enterprise Applicant Tracking System —
the kind built to screen **500K applicants per job posting** — running entirely on
Docker Compose: event-driven pipeline on Kafka, CQRS-lite read models, hybrid
keyword+semantic search, LLM extraction & rerank, fraud detection, and explainable
scoring.

The architecture is the production architecture; scale is the only demo concession.
Swapping MinIO→S3, Compose→K8s+KEDA, and Ollama→Bedrock takes the same design to
500K — config, not redesign.

**Honest demo simplifications:** one seeded demo recruiter account (sign-in since Part 2, no SSO), no ClamAV
(magic-byte validation instead), no Debezium (simple polling outbox), no
field-level encryption, no Kubernetes. The 10K load test reads its synthetic
resumes with **rules instead of the LLM** (free-tier Groq allows ~1,000 calls a
day); every real upload still goes through the LLM. The AI rerank runs in the API
service rather than the matcher (the matcher has no HTTP surface).

> The rehearsed interview walkthrough, with measured numbers: [DEMO.md](DEMO.md).

> Build status by phase: see [PROGRESS.md](PROGRESS.md).

## Run it

```bash
docker compose up -d --build
```

| Service | Where |
|---|---|
| API | http://localhost:8082 — health at `/actuator/health` |
| Kafka UI (lag visualizer) | http://localhost:8085 |
| MinIO console | http://localhost:9001 (minioadmin / minioadmin) |
| Postgres (pgvector) | localhost:5434 (ats / ats) |
| Frontend (container, nginx) | http://localhost:5174 |
| Ollama (from Phase 4, profile `llm`) | http://localhost:11435 |
| OpenSearch (from Phase 7, profile `search`) | http://localhost:9200 |

Host ports are picked to coexist with other local stacks; inside the compose
network the services still use their native ports (`api:8080`, `postgres:5432`,
`ollama:11434`).

No `.env` needed — compose ships working defaults; copy `.env.example` to `.env`
to override.

## How it's put together

**Event-driven microservices, one Maven module per service.** The backend is a
multi-module reactor: `ats-common` (shared contracts library — domain entities,
Kafka topic catalog, event envelope, transactional outbox, Flyway migrations)
plus one independently built and deployable Spring Boot service per pipeline
stage, each with its own Docker image:

| Service | Pipeline stage (design-board name) |
|---|---|
| `api-service` | HTTP edge: Application + Job + Upload services (merged for the demo) |
| `parser-service` (Phase 3) | Parsing Service — PDF/DOCX/OCR → clean text |
| `extractor-service` (Phase 4) | Extraction Service — LLM → structured profile |
| `matcher-service` (Phase 5) | Matching Service — explainable scoring |
| `dedup-service` (Phase 6) | Deduplication Service |
| `fraud-service` (Phase 6) | Trust Service — fraud heuristics, flags never auto-reject |
| `embedder-service` (Phase 7) | Embeddings — semantic vectors |
| `indexer-service` (Phase 7) | Search Service — OpenSearch read model (CQRS) |

Services never call each other synchronously — every hop is a Kafka event, which
is what makes `docker compose up -d --scale parser=8` a one-liner. The one
deliberate compromise vs. purist microservices: a shared PostgreSQL instance
(every stage enriches the same application record); splitting data ownership
per service is the named next step at production scale.

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

### Phase 2 — Apply & upload flow (presigned uploads)
- REST: `POST /api/candidates`, `POST /api/jobs/{jobId}/applications` (creates the
  application as APPLIED + `application.created` via the outbox),
  `POST /api/applications/{id}/resume/upload-url` (10-minute presigned PUT to
  `resumes/{jobId}/{appId}/v{n}/resume.{ext}` — bytes go straight to MinIO, never
  through the API), `POST .../resume/complete` (HEAD + magic-byte allowlist
  PDF/DOCX/PNG/JPG + ≤10 MB + streamed SHA-256 → resumes row + `resume.uploaded`).
- Transactional outbox is live: business rows and events commit together; a 500 ms
  relay (`FOR UPDATE SKIP LOCKED`, at-least-once) publishes envelopes
  `{event_id, event_type, occurred_at, application_id, job_id, data, schema_version}`.
- Flyway V2 seeds the demo job "Backend Engineer — Java/Kafka" (fixed UUID
  `...000000000001`) with structured must-have/nice-to-have requirements.
- `tools/seed.sh N` generates N synthetic resumes in a throwaway Python container
  (varied text templates, image-only OCR targets, 1 corrupt file and 3 planted-fraud
  resumes at N≥10) and drives the real API end to end.

### Phase 3 — Parser worker (PDF/DOCX/OCR)
- `parser-service` (the first worker microservice) consumes `resume.uploaded`:
  PDFBox extracts text while a custom stripper records every character's font
  size and ink color — hidden white/1pt keyword stuffing shows up as counters
  plus a captured sample in `parsed.json`, stored beside the resume in MinIO.
- Image-only resumes (< 100 chars/page) fall back to OCR: pages render at
  200 DPI and Tesseract reads them, with mean word confidence saved to
  `resumes.ocr_confidence`. DOCX goes through Apache Tika. Only this service's
  image carries tesseract.
- The trust rules are now real: consumers are idempotent via the
  `processed_events` ledger, and failures retry 3× then land in
  `resume.uploaded.dlq` with exception headers — a DLQ listener marks the
  application `PARSE_FAILED` with the error in its audit trail. The planted
  corrupt file proves the whole path (exactly 1 DLQ message per 20-seed).
- Success path: application → `PARSED`, `resume.parsed` emitted through the
  outbox for the Phase 4 extractor.

### Phase 4 — Extraction worker (LLM → structured profile)
- `extractor-service` turns parsed resume text into one canonical JSON profile
  per application via a **pluggable LLM layer** (`LLM_PROVIDER=ollama|groq|gemini`
  — local qwen2.5:3b by default, cloud swap is one env var; Bedrock at scale is
  the same trick).
- The LLM is never trusted: prompts declare resume text as *data, not
  instructions* (injection defense), answers are validated against a JSON
  Schema with one corrective retry, missing contacts get regex-recovered, and
  all math — overlap-aware experience months, seniority, completeness — is
  computed in Java, never by the model.
- Skills are normalized through a 150-entry taxonomy (aliases + first-release
  years); unknown spellings land in `unmatched_skills` (the taxonomy's to-do
  list — it caught OCR misreads like "rabbitmag" on day one).
- Battle scars encoded in code: Groq's 429 rate limits are treated as
  backpressure (sleep-and-retry per the server's hint), cloud model names are
  config because providers retire them, and `max-poll-records=1` keeps slow
  LLM calls inside Kafka's poll deadline. One stubborn resume that failed on
  two models was rescued by a third — pluggability as resilience, live.

### Phase 5 — Scoring engine + Recruiter Dashboard v1 (first full end-to-end demo)
- `matcher-service` scores every extracted profile against its job: six
  explainable components (skill match with **recency decay**, experience fit,
  project relevance, education, certifications, resume quality) blended by
  weights that **shift for fresher-friendly jobs** (experience's weight flows
  to projects/education). Missing must-have skills don't just lower a number —
  they **cap the final score**; hopeless mismatches are knocked out but stay
  visible. Every verdict ships with human-readable evidence chips.
- The React dashboard (Vite + Tailwind, ink-navy/marigold) shows the ranked
  table with component bars, a full report drawer (evidence, profile, audit
  timeline) and shortlist/reject buttons; new candidates **stream in live over
  SSE** as the matcher scores them — api-service relays `application.scored`
  events to open browser connections.
- A satisfying accident of honest data: the two planted fraud resumes rank
  #1 and #2 on the backend job (hidden keyword stuffing inflates skill match).
  Phase 6's Trust Service exists to take them down.

### Phase 6 — Trust layer: fraud heuristics + deduplication
- `fraud-service` (the design board's **Trust Service**) checks every extracted
  resume for things that can't all be true: two full-time jobs running in
  parallel, more years claimed than have passed since graduation, technology
  used before it was released, text hidden from humans (tiny or white ink —
  the parser records the font/colour fingerprint), and throwaway inboxes. Each
  signal has a weight; they combine like independent evidence
  (`1 − Π(1 − w)`), and a score ≥ 0.5 takes up to 50 points off the ranking and
  sends the application to a **Review Queue**. It only ever flags — a human
  clears (points come back) or confirms (rejected), and a clearance survives
  later re-checks unless genuinely new evidence appears.
- `dedup-service` finds the same person (email/phone), the same file under a
  different name (SHA-256), and copied work history: **MinHash** squeezes each
  work history into 128 numbers whose agreement estimates text overlap, and
  **LSH banding** means a new resume is only compared with the few that share a
  bucket — not all 500K. Look-alikes share a cluster id; findings flow into the
  trust score.
- Honest results: on the 34 resumes already in the system, exactly the 6
  planted fakes were flagged and the former top 4 — all fakes — dropped out of
  the lead. Building it also surfaced a real bug: PDFBox never tracked fill
  colours, so white-ink text had been invisible to the detector since Phase 3.
- Dashboard: shield badges and "−35 fraud" under scores, every flag explained
  in plain English in the drawer, the Review Queue page, and a Live Flow river
  that shows matcher, dedup and trust working in parallel.

### Phase 7 — Hybrid search: keywords + meaning (CQRS read model)
- Start it with `docker compose --profile search up -d` — OpenSearch, Ollama
  (the embedding model is pulled automatically), the embedder and the indexer.
- `embedder-service` turns every resume into 768 numbers that capture its
  **meaning** (Ollama `nomic-embed-text`), stored in pgvector. Texts about the
  same thing land close together even with different words.
- `indexer-service` keeps a search-shaped copy of every candidate in
  **OpenSearch** — the read side of CQRS. It re-reads Postgres for each change,
  so the copy is always the current truth, and Kafka batches the work (up to
  500 events or 2 seconds per bulk request).
- `GET /api/jobs/{id}/search?q=&skills=&minExp=` runs keyword search (BM25) and
  meaning search (kNN) side by side and merges them with **Reciprocal Rank
  Fusion** — positions, not scores, so the two scales never have to agree.
  Every result says where it came from: `keyword`, `semantic` or `both`.
- The proof: "event driven backend" finds candidates whose resumes never say
  "event driven" but describe Kafka work — labelled `semantic`.
- Built to degrade, not break: without Ollama it answers keyword-only (and says
  so); without OpenSearch it returns a clear 503 while the rest keeps working.
- Dashboard: a **Talent Search** page with a filter sidebar, match-source badges
  and per-list ranks.

### Phase 8 — LLM rerank, 10K load test, and what it taught us
- **LLM rerank** (`POST /api/jobs/{id}/rerank`): the top 50 candidates go to the
  model in compact evidence cards (5 per call, verdict-first schema) and each
  gets a 3-line summary, must-have coverage, depth vs breadth, red flags — and a
  score nudge of **at most ±10%**, enforced in code and by a database CHECK.
  Runs in the background; results stream to the page over SSE.
- **Fairness, found the hard way:** the model kept writing "overqualified —
  flight risk". The cause was our own Phase 5 formula, which docked experience
  for anyone with 3× the requirement and printed that label — an
  age-discrimination proxy that hit 263 of 359 candidates. Removed, everyone
  re-scored by replaying Kafka, and any AI verdict that still leans on such a
  reason is discarded whole (the candidate keeps the formula score).
- **10,000 resumes through the real API** in ~11 minutes with 0 errors
  (`./tools/seed.sh --bulk 10000`, 20 parallel workers), then
  `--scale parser=8 --scale extractor=4`. The load test found two real bugs:
  Postgres ran out of connections (workers × a default pool of 10 > 100), and the
  parser's 500-record batches outlived Kafka's poll interval so progress was
  never committed. Both fixed; the backlog drains to 0. It also showed the limits
  of one laptop honestly — partition skew and a saturated CPU meant no clean 8×
  speed-up here (see DEMO.md for how to measure it properly).
- Real resumes are protected throughout: bulk uploads take the name and email
  from the resume itself, extraction falls back to a stronger model when the
  small one returns invalid JSON, and network blips are waited out instead of
  failing the resume.

## From this laptop to 500K applicants per job

| Here | At 500K | Measured reason |
|---|---|---|
| Compose + `--scale` | Kubernetes + KEDA scaling on Kafka lag | lag is the load signal Live Flow already shows |
| 12 partitions | ≥ max consumers | one partition = one consumer at a time |
| Postgres (200 conns) | + PgBouncer, read replicas | 8 parsers already exhausted 100 connections |
| MinIO | S3 | same SDK — an env change |
| Groq free tier / Ollama | Bedrock-style provisioned throughput | ~1,000 calls/day free; 500K × ~2.5–3K tokens ≈ 1.25–1.5B tokens |
| CPU embeddings, 0.27 s each | GPU / managed embeddings | 500K × 0.27 s ≈ 37 CPU-hours |
| Polling outbox | Debezium CDC | lower latency, no polling |
