#!/bin/sh
# One load-test resume through the API — run by `seed.sh --bulk` INSIDE a Linux container
# (curlimages/curl) on the compose network, many in parallel via xargs -P.
# Why a container: on Windows/Git Bash every curl/grep spawn costs 100-300ms and parallel
# workers serialize on it (measured 2026-10-18: 0.67 resumes/s from the host shell).
#
# Usage: bulk-worker.sh "<file>\t<name>\t<email>\t<phone>\t<kind>"
# Prints "ok <application> <kind>" or "err <step> <file>"; never fails the whole run.
API=${API:-http://api:8080}
OUT=${OUT:-/tools/out}
JOB_ID=${JOB_ID:?JOB_ID required}

line=$1
tab=$(printf '\t')
file=$(echo "$line" | cut -d"$tab" -f1)
name=$(echo "$line" | cut -d"$tab" -f2)
email=$(echo "$line" | cut -d"$tab" -f3)
phone=$(echo "$line" | cut -d"$tab" -f4)
kind=$(echo "$line" | cut -d"$tab" -f5)

field() { # field "$json" name → value of "name":"value"
    echo "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p" | head -n 1
}

cand=$(curl -sf -X POST "$API/api/candidates" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$name\",\"email\":\"$email\",\"phone\":\"$phone\"}") || { echo "err candidate $file"; exit 0; }
cid=$(field "$cand" id)
app=$(curl -sf -X POST "$API/api/jobs/$JOB_ID/applications" -H 'Content-Type: application/json' \
    -d "{\"candidateId\":\"$cid\",\"loadTest\":true}") || { echo "err application $file"; exit 0; }
aid=$(field "$app" id)
up=$(curl -sf -X POST "$API/api/applications/$aid/resume/upload-url" -H 'Content-Type: application/json' \
    -d "{\"filename\":\"$file\"}") || { echo "err upload-url $file"; exit 0; }
url=$(field "$up" url)
key=$(field "$up" key)
ct=$(field "$up" contentType)
# The link is signed for localhost:9000 (the host's view). Inside the network that's minio:9000:
# --connect-to dials minio but keeps the signed Host header, so the signature stays valid.
curl -sf --connect-to localhost:9000:minio:9000 -T "$OUT/$file" -H "Content-Type: $ct" "$url" \
    || { echo "err put $file"; exit 0; }
curl -sf -X POST "$API/api/applications/$aid/resume/complete" -H 'Content-Type: application/json' \
    -d "{\"key\":\"$key\"}" > /dev/null || { echo "err complete $file"; exit 0; }
echo "ok $aid $kind"
