#!/usr/bin/env bash
# Drives the REAL API end to end for N synthetic resumes:
#   generate PDFs (python container) -> create candidate -> create application
#   -> presigned PUT to MinIO -> complete upload.
# Usage: ./tools/seed.sh N        (env: API, JOB_ID, SEED to override)
set -euo pipefail

N=${1:?usage: seed.sh N}
API=${API:-http://localhost:8080}
JOB_ID=${JOB_ID:-00000000-0000-0000-0000-000000000001}
SEED=${SEED:-$RANDOM}   # varies per run so re-seeding never reuses emails; set SEED=42 for reproducibility

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT="$SCRIPT_DIR/out"
mkdir -p "$OUT"
# Windows-style path for docker -v when under Git Bash; plain pwd elsewhere
TOOLS_HOST=$(cd "$SCRIPT_DIR" && (pwd -W 2>/dev/null || pwd))

curl -sf "$API/actuator/health" > /dev/null || { echo "ERROR: API not reachable at $API"; exit 1; }

echo ">> generating $N resumes (seed=$SEED)..."
MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$TOOLS_HOST:/tools" -v ats-pip-cache:/root/.cache/pip -w /tools \
    python:3.12-slim sh -c \
    "pip install -q reportlab pillow && python generate_resumes.py --count $N --seed $SEED --out out"

json_field() { # json_field "$json" fieldName -> string value
    grep -o "\"$2\":\"[^\"]*\"" <<<"$1" | head -1 | cut -d'"' -f4
}

i=0
while IFS=$'\t' read -r file name email phone kind; do
    i=$((i + 1))
    cand=$(curl -sf -X POST "$API/api/candidates" -H 'Content-Type: application/json' \
        -d "{\"name\":\"$name\",\"email\":\"$email\",\"phone\":\"$phone\"}")
    cid=$(json_field "$cand" id)

    app=$(curl -sf -X POST "$API/api/jobs/$JOB_ID/applications" -H 'Content-Type: application/json' \
        -d "{\"candidateId\":\"$cid\"}")
    aid=$(json_field "$app" id)

    up=$(curl -sf -X POST "$API/api/applications/$aid/resume/upload-url" -H 'Content-Type: application/json' \
        -d "{\"filename\":\"$file\"}")
    url=$(json_field "$up" url)
    key=$(json_field "$up" key)
    ct=$(json_field "$up" contentType)

    curl -sf -T "$OUT/$file" -H "Content-Type: $ct" "$url"
    curl -sf -X POST "$API/api/applications/$aid/resume/complete" -H 'Content-Type: application/json' \
        -d "{\"key\":\"$key\"}" > /dev/null

    echo "  [$i/$N] $name <$email> ($kind) -> application $aid"
done < "$OUT/manifest.tsv"

echo ">> done: $i applications submitted through the API"
