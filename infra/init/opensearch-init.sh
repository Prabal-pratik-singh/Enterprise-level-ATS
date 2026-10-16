#!/bin/sh
# One-shot: install the candidates index template, then create the index once.
set -e
OS=http://opensearch:9200
curl -sf -X PUT "$OS/_index_template/candidates" -H 'Content-Type: application/json' \
     --data-binary @/init/opensearch/candidates-template.json
echo
code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$OS/candidates")
# 200 = created now, 400 = already exists from an earlier start — both are fine
[ "$code" = "200" ] || [ "$code" = "400" ] || { echo "index create failed: HTTP $code"; exit 1; }
echo "candidates index ready (HTTP $code)"
