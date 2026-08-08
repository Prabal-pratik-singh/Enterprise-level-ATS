#!/bin/sh
# One-shot bucket bootstrap: creates the 'resumes' bucket, then exits.
set -e

until mc alias set local http://minio:9000 "${MINIO_ROOT_USER:-minioadmin}" "${MINIO_ROOT_PASSWORD:-minioadmin}" > /dev/null 2>&1; do
    echo "minio-init: waiting for minio..."
    sleep 2
done

mc mb --ignore-existing local/resumes
echo "minio-init: bucket 'resumes' ready"
