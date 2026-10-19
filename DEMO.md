# DEMO — the rehearsed walkthrough

About 30 minutes. Every number below was **measured on this laptop** (16 CPUs, 11.7 GB for
Docker) on 2026-10-19 — nothing is a projection unless it says so.

Before you start:
- `.env` has a working `GROQ_API_KEY` (check without printing it — see "Keys" at the end).
- Free memory: the 8-parser step needs ~4.5 GB extra. Close heavy apps or stop other Docker
  stacks first (`docker ps` to see what runs).
- The stronger model's free tier is **200,000 tokens/day**; one full AI rerank uses ~35–40K.
  **Run the rerank once per demo**, not repeatedly.

---

## 1. Fresh start

```bash
docker compose --profile search up -d     # ~15 long-running containers (+3 one-shot setup jobs)
```
Open:
- Dashboard — http://localhost:5174
- Kafka UI — http://localhost:8085
- Live Flow — http://localhost:5174/flow

**Say:** "Eight independent Spring Boot services (api, parser, extractor, matcher, fraud, dedup, embedder, indexer). They never call each other — every hop is a Kafka
event, written through a transactional outbox, so a crash can't lose one."

## 2. Seed 10,000 resumes

```bash
./tools/seed.sh --bulk 10000
```
- The generator renders 10,000 PDFs on all cores in **~40 s** (5% are image-only scans → OCR).
- 20 parallel workers push them through the **real API** (candidate → application → presigned
  upload to MinIO → complete): **10,000 in ~11 minutes, 0 API errors**.

**Say:** "Resume bytes never touch the API — the browser (here: the seeder) uploads straight to
S3-compatible storage with a presigned URL; the API only records the event."

**Be honest about one thing:** these load-test resumes are read by **rules, not the LLM**
(`load_test` flag). Free-tier Groq allows ~1,000 calls/day — 10,000 resumes through the LLM would
take 10+ days. Real uploads always use the LLM, even mid-load-test.

## 3. Watch the backlog climb

- **Kafka UI** → Consumers → `parser-service`: lag grows (measured: ~25–35 per second at the peak of seeding).
- **Live Flow**: the parser queue swells; the header pill turns into **"N queued"**.

**Say:** "The parser is the bottleneck by design — one instance drains ~3.3 resumes/second."

## 4. Scale out

```bash
docker compose up -d --no-recreate --scale parser=8 --scale extractor=4 parser extractor
```
Containers start in ~30–60 s and Kafka splits the 12 partitions between them.

## 5. Watch it drain

Lag falls to 0 (`curl -s localhost:8082/api/admin/lag`). Then scale back:
```bash
docker compose up -d --no-recreate --scale parser=1 --scale extractor=1 parser extractor
```

**Tell the story of what this step taught us** (the best part of the demo):

1. **The database ran out of connections.** 8 parsers + 4 extractors × Spring's default pool of 10
   ≈ 190 connections; Postgres allowed 100. New parsers crash-looped on "too many clients", and
   every crash made Kafka re-shuffle partitions among *all* parsers. Fix: workers pool 4
   connections, Postgres allows 200 (86 → 40 connections for the same services). In production:
   **PgBouncer**.
2. **The parser never committed its progress.** It polled 500 records per batch; a batch could
   outlast Kafka's 5-minute `max.poll.interval`, so Kafka kicked it out mid-batch and the work was
   never recorded — the backlog didn't shrink. Fix: `max-poll-records: 20`.
3. **Partitions cap parallelism.** After the fixes, the leftover backlog sat in only 4 of 12
   partitions, so 4 of the 8 parsers idled. One partition = one consumer at a time.
4. **The laptop is CPU-bound.** At the peak ~15 of 16 cores were busy — Ollama alone used 5,
   embedding all 10K resumes in parallel with parsing.

**Honest result:** after the fixes the backlog drained to 0 with no errors, but this laptop did
**not** show a clean 8× speed-up (8 parsers ≈ 3.5/s vs 1 parser ≈ 3.3/s, for reasons 3 and 4).
To measure the real speed-up: on a machine with RAM to spare, seed a fresh batch (so all 12
partitions hold work), stop the embedder during the measurement, time 60 s with 1 parser, then
with 8.

## 6. Search, rerank and review

1. **Talent Search** (`/search`): type **"event driven backend"**. Results are labelled `keyword`,
   `semantic` or `both`. Point at a **semantic** hit: their resume never says "event driven" but
   describes Kafka work. Keyword (BM25) + meaning (kNN, 768-dim embeddings) merged by Reciprocal
   Rank Fusion.
2. **Rerank** (job page → "Rerank top 50 with AI", ~4–6 minutes): the AI reviews the top 50 like a
   senior recruiter and nudges each score by **at most ±10%** (enforced in code *and* by a database
   CHECK). Open a candidate → **AI review**: 3-line summary, depth vs breadth, red flags, must-have
   coverage.
3. **Review Queue** (`/review`): fraud the Trust Service flagged — hidden white text, impossible
   timelines, throwaway emails, duplicate files, copied work history. The system only flags;
   **Clear** gives the points back, **Confirm** rejects.

**Say (fairness — interviewers love this):** "The AI kept writing 'overqualified — flight risk'.
Prompt fixes barely helped, because the bias came from *our own formula*: since Phase 5 the
matcher cut the experience score for anyone with 3× the required experience and printed that
label. That's an age-discrimination proxy. It hit 263 of 359 candidates. I removed it, re-scored
everyone by replaying Kafka (no hand-edited data), and kept a guard that throws away any AI
verdict that still uses such a reason. Lesson: when an AI repeats a bias, check what you fed it."

## 7. Talking points — from this laptop to 500K applicants per job

| Demo here | At 500K scale | Why |
|---|---|---|
| Docker Compose, `--scale parser=8` | Kubernetes + **KEDA** autoscaling on Kafka lag | lag is the honest load signal — exactly what the Live Flow shows |
| 12 partitions | ≥ the max number of consumers (e.g. 100+) | step 5, lesson 3: partitions cap parallelism |
| Postgres, 200 connections | Postgres + **PgBouncer**, read replicas | step 5, lesson 1 |
| MinIO | **S3** | same SDK — an env change |
| Groq free tier / Ollama | **Bedrock** (or similar) with provisioned throughput | free tier ≈ 1,000 calls/day; 500K resumes × ~2.5–3K tokens ≈ 1.25–1.5 billion tokens |
| Ollama embeddings on CPU (~0.27 s each) | GPU or a managed embedding service | 500K × 0.27 s ≈ 37 CPU-hours on one instance |
| One parser ≈ 3.3 resumes/s | many parsers, partitions to match | 500K ÷ 3.3/s ≈ 42 hours on one parser — this is why it's a queue, not a request |
| Polling outbox relay | **Debezium** CDC | lower latency, no polling |

Every number in the right-hand column is arithmetic on a measurement above, not a benchmark.

---

## Keys (check without printing them)

```bash
docker run --rm --env-file .env curlimages/curl:8.11.1 sh -c \
  'curl -s -o /dev/null -w "Groq HTTP %{http_code}\n" -H "Authorization: Bearer $GROQ_API_KEY" https://api.groq.com/openai/v1/models'
```
Never `env | grep GROQ_` — that prints the key.
