# Project guide

Event-driven Applicant Tracking System that runs on one laptop (Docker Compose). Phase history
and every design decision: [PROGRESS.md](PROGRESS.md). Interview walkthrough: [DEMO.md](DEMO.md).

## Commands (all verified on Windows 11 + Git Bash; the repo path contains a space — quote it)

There is no host Maven and the host Java is 25; the backend targets **Java 21**, so build and
test inside the Maven container. `MSYS_NO_PATHCONV=1` stops Git Bash from rewriting paths.

| What | Command |
|---|---|
| Run everything | `docker compose --profile search up -d` (core only: `docker compose up -d`) |
| Dashboard / API | http://localhost:5174 · http://localhost:8082 (health: `/actuator/health`) |
| Kafka UI / MinIO / OpenSearch | http://localhost:8085 · http://localhost:9001 · http://localhost:9200 |
| Backend tests (one module + deps) | `MSYS_NO_PATHCONV=1 docker run --rm -v ats-m2:/root/.m2 -v "<abs path>/backend":/src -w /src maven:3.9-eclipse-temurin-21 mvn -B -q -pl <module> -am test` |
| Test results | `<module>/target/surefire-reports/*.xml` (the `-q` run prints nothing on success) |
| Type check (backend) | the same Maven run — compilation is the type check |
| Frontend build check | `cd frontend && npx vite build && rm -rf dist` |
| Lint | none configured (no Checkstyle / ESLint) |
| Rebuild + redeploy one service | `docker compose build <svc> && docker compose up -d --no-deps <svc>` |
| Redeploy the UI | `docker compose up -d --build --no-deps frontend` (without `--no-deps` it also rebuilds the API) |
| Seed resumes (LLM path) | `./tools/seed.sh N` |
| Load test (rules path) | `./tools/seed.sh --bulk N` (runs inside a container; ~100 resumes per 6 s) |
| Scale out / back | `docker compose up -d --no-recreate --scale parser=8 --scale extractor=4 parser extractor` (then `=1`) |
| Recruiter token for curl | see "Recruiter token" in DEMO.md (signs in with `RECRUITER_*` from `.env`) |
| Queue backlog | `curl -s localhost:8082/api/admin/lag -H "Authorization: Bearer $TOKEN"` |
| Database shell | `docker exec -it ats-postgres-1 psql -U ats -d ats` |

## Architecture (10 lines)

- `backend/` is a Maven reactor: `ats-common` (entities, topic catalog, event envelope, transactional
  outbox + relay, Flyway migrations V1–V9, shared clients) + 8 Spring Boot services.
- Flow: api → `resume.uploaded` → parser → `resume.parsed` → extractor (LLM, or rules for load tests)
  → `resume.extracted` → fan-out to matcher, fraud, dedup and embedder in parallel.
- matcher writes `match_score`, fraud writes `fraud_penalty`, the API rerank writes
  `rerank_adjustment`; Postgres derives `final_score` as a generated column.
- indexer keeps the OpenSearch read model (CQRS) from `application.scored` / `resume.embedded` /
  `application.flagged` / `shortlist.updated`; the API serves hybrid search (BM25 + kNN, RRF).
- Services never call each other; every hop is a Kafka event written through the outbox.
- Every consumer is idempotent via `processed_events` (the indexer re-reads Postgres instead).
- `frontend/` is React + Vite + Tailwind, served by nginx on 5174 with `/api` proxied (SSE unbuffered).
- api-service also signs people in (`com.ats.auth`): 15-minute JWT access tokens + a rotating 7-day
  refresh cookie. Every endpoint not listed as public in `SecurityConfig` is recruiter-only.

## Traps

- **Adding a module:** add a `COPY <module>/pom.xml` line to EVERY service Dockerfile, or every image
  build fails (the parent pom lists modules that must exist).
- **Shell scripts** must stay LF (`.gitattributes` enforces `*.sh eol=lf`); they are bind-mounted
  into Linux containers. `*.pdf` is binary.
- **Never print secrets:** do not `env | grep GROQ_` — it prints the API key. DEMO.md shows a safe check.
  Never `source .env` either: a malformed line makes the shell echo it, value included.
- **Sign-in config:** the API refuses to start without `JWT_SECRET` (32+ characters) in `.env`. The demo
  recruiter is created once from `RECRUITER_EMAIL` / `RECRUITER_PASSWORD`; changing them in `.env`
  later does not change an existing account. The UI reads SSE through `fetch` (EventSource cannot
  send the token) — do not reintroduce `EventSource`.
- **Groq free tier:** ~1,000 requests/day and 8,000 tokens/min per model; `gpt-oss-120b` (the
  fallback / rerank model) also has a 200,000 tokens/day cap — one full rerank ≈ 35–40K tokens.
- **The network here is flaky** (DNS failures to Maven Central / Groq). Image builds can sit
  20+ minutes on `dependency:go-offline`; retries succeed. LLM calls wait out DNS errors by design.
- **Kafka consumers:** keep `max-poll-records` small for slow work (parser 20, extractor 1) — a batch
  must finish inside the 5-minute `max.poll.interval`, or the member is kicked and never commits.
- **Postgres connections:** workers pool 4 (`SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`), Postgres
  allows 200. Scaling a worker multiplies its pool.
- **Re-run a consumer over history** (e.g. after a scoring change): stop it, delete its rows from
  `processed_events` (consumer name, e.g. `matcher`), `kafka-consumer-groups.sh --reset-offsets
  --to-earliest --group <group> --topic <topic> --execute`, start it. Never hand-edit scores.
- **Git Bash process spawning is slow** (100–300 ms per `curl`/`grep`); bulk tooling runs inside a container.
- **`Score` is `@DynamicUpdate`** and `final_score` is generated: three services write disjoint columns
  of one row — never map `final_score` as writable.
- **Fairness rule:** more experience is never a minus. Do not reintroduce "overqualified" penalties
  (removed 2026-10-19 — see PROGRESS.md); the rerank discards verdicts that use such proxies.

## Review non-issues (deliberate demo choices — do not flag)

- The Part 1 apply flow (`POST /api/candidates`, `/api/jobs/*/applications`, resume `upload-url` /
  `complete`) is public on purpose: applicants never need an account, and `seed.sh` uses it.
- Polling outbox relay instead of Debezium; magic-byte file checks instead of ClamAV.
- One shared Postgres for all services (per-service data ownership is the documented next step).
- Load-test applications (`load_test = true`) are read by rules, not the LLM, because of free-tier limits.
- Placeholder emails ending in `@intake.invalid` for bulk uploads until the resume is read.
- Synthetic resumes are reproducible from `SEED`; the generator plants frauds and duplicates on purpose.
