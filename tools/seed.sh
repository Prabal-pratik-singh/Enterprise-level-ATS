#!/usr/bin/env bash
# Drives the REAL API end to end for N synthetic resumes:
#   generate PDFs (python container) -> create candidate -> create application
#   -> presigned PUT to MinIO -> complete upload.
# Usage: ./tools/seed.sh N              one at a time; read by the LLM like any real resume
#        ./tools/seed.sh --bulk N       Phase 8 load test: 20 parallel workers, marked load_test
#                                       (the extractor reads them with rules — the free LLM tier
#                                       allows 1,000 calls/day, far below 10,000 resumes)
# env: API, JOB_ID, SEED, WORKERS (bulk, default 20), IMAGE_SHARE (bulk, default 0.05)
set -euo pipefail

BULK=false
if [ "${1:-}" = "--bulk" ]; then
    BULK=true
    shift
fi
N=${1:?usage: seed.sh [--bulk] N}
API=${API:-http://localhost:8082}
JOB_ID=${JOB_ID:-00000000-0000-0000-0000-000000000001}
SEED=${SEED:-$RANDOM}   # varies per run so re-seeding never reuses emails; set SEED=42 for reproducibility
WORKERS=${WORKERS:-20}
IMAGE_SHARE=${IMAGE_SHARE:-0.05}

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT="$SCRIPT_DIR/out"
mkdir -p "$OUT"
# Windows-style path for docker -v when under Git Bash; plain pwd elsewhere
TOOLS_HOST=$(cd "$SCRIPT_DIR" && (pwd -W 2>/dev/null || pwd))

curl -sf "$API/actuator/health" > /dev/null || { echo "ERROR: API not reachable at $API"; exit 1; }

GEN_ARGS="--count $N --seed $SEED --out out"
if $BULK; then
    rm -f "$OUT"/resume_*.pdf "$OUT"/manifest.tsv   # a fresh 10K set, not mixed with old files
    GEN_ARGS="$GEN_ARGS --fast --image-share $IMAGE_SHARE"
fi
echo ">> generating $N resumes (seed=$SEED)..."
MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$TOOLS_HOST:/tools" -v ats-pip-cache:/root/.cache/pip -w /tools \
    python:3.12-slim sh -c \
    "pip install -q reportlab pillow && python generate_resumes.py $GEN_ARGS"

json_field() { # json_field "$json" fieldName -> string value
    grep -o "\"$2\":\"[^\"]*\"" <<<"$1" | head -1 | cut -d'"' -f4
}

# One resume through the API. Prints "ok <application>" or "err <step> <file>" — never exits the run.
seed_one() {
    local line=$1 load_test=$2
    local file name email phone kind cand cid app aid up url key ct
    IFS=$'\t' read -r file name email phone kind <<<"$line"
    cand=$(curl -sf -X POST "$API/api/candidates" -H 'Content-Type: application/json' \
        -d "{\"name\":\"$name\",\"email\":\"$email\",\"phone\":\"$phone\"}") || { echo "err candidate $file"; return 0; }
    cid=$(json_field "$cand" id)
    app=$(curl -sf -X POST "$API/api/jobs/$JOB_ID/applications" -H 'Content-Type: application/json' \
        -d "{\"candidateId\":\"$cid\",\"loadTest\":$load_test}") || { echo "err application $file"; return 0; }
    aid=$(json_field "$app" id)
    up=$(curl -sf -X POST "$API/api/applications/$aid/resume/upload-url" -H 'Content-Type: application/json' \
        -d "{\"filename\":\"$file\"}") || { echo "err upload-url $file"; return 0; }
    url=$(json_field "$up" url)
    key=$(json_field "$up" key)
    ct=$(json_field "$up" contentType)
    curl -sf -T "$OUT/$file" -H "Content-Type: $ct" "$url" || { echo "err put $file"; return 0; }
    curl -sf -X POST "$API/api/applications/$aid/resume/complete" -H 'Content-Type: application/json' \
        -d "{\"key\":\"$key\"}" > /dev/null || { echo "err complete $file"; return 0; }
    echo "ok $aid $kind $name"
}

if ! $BULK; then
    i=0
    while IFS= read -r line; do
        i=$((i + 1))
        result=$(seed_one "$line" false)
        case "$result" in
            ok*) echo "  [$i/$N] $(cut -d' ' -f4- <<<"$result") ($(cut -d' ' -f3 <<<"$result")) -> application $(cut -d' ' -f2 <<<"$result")" ;;
            *) echo "  [$i/$N] ERROR: $result"; exit 1 ;;
        esac
    done < "$OUT/manifest.tsv"
    echo ">> done: $i applications submitted through the API"
    exit 0
fi

# ---- bulk: WORKERS resumes in flight at once, inside a Linux container on the compose network ----
# (Git Bash on Windows spends 100-300ms per curl/grep spawn and parallel workers serialize on it;
#  inside the container the same work is ~15x faster — see tools/bulk-worker.sh)
RESULTS="$OUT/bulk-results.log"
: > "$RESULTS"
start=$(date +%s)
echo ">> submitting $N resumes with $WORKERS parallel workers (marked load_test)..."
( while sleep 10; do
      done_n=$(wc -l < "$RESULTS")
      echo "   ... $done_n / $N submitted ($(( $(date +%s) - start ))s)"
      [ "$done_n" -ge "$N" ] && break
  done ) &
progress=$!
MSYS_NO_PATHCONV=1 docker run --rm --network "${COMPOSE_NETWORK:-ats_default}"     -v "$TOOLS_HOST:/tools:ro" -e JOB_ID="$JOB_ID" -e API="${BULK_API:-http://api:8080}"     --entrypoint sh curlimages/curl:8.11.1 -c     "xargs -P $WORKERS -I{} sh /tools/bulk-worker.sh '{}' < /tools/out/manifest.tsv" >> "$RESULTS"
kill "$progress" 2>/dev/null || true

ok=$(grep -c '^ok ' "$RESULTS" || true)
errors=$(grep -c '^err ' "$RESULTS" || true)
echo ">> done in $(( $(date +%s) - start ))s: $ok submitted, $errors API errors"
if [ "$errors" -gt 0 ]; then
    echo "   first errors:"; grep '^err ' "$RESULTS" | head -5 | sed 's/^/   /'
    exit 1
fi
