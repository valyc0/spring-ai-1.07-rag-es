#!/usr/bin/env bash
#
# Chiama GET /search (RAG completo: embed -> kNN su Elasticsearch -> risposta LLM)
# e mostra request, response e timing.
#
#   ./scripts/search.sh                              # domanda di default
#   ./scripts/search.sh "chi ha scritto il libro?"   # domanda custom
#   BASE_URL=http://host:8080 ./scripts/search.sh
#
# Override: BASE_URL, QUERY

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
QUERY="${1:-${QUERY:-A che ora parte il treno per Napoli?}}"

echo "=== REQUEST ==="
printf '\033[2mcurl -G "%s/search" --data-urlencode "q=%s"\033[0m\n' "$BASE_URL" "$QUERY"
echo

code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/" || true)
if [[ "$code" == "000" ]]; then
	printf '\033[31ml app non raggiungibile su %s (mvn spring-boot:run)\033[0m\n' "$BASE_URL" >&2
	exit 1
fi

headers=$(mktemp)
body_file=$(mktemp)
trap 'rm -f "$headers" "$body_file"' EXIT

# -D dumpa gli header su file, -o il body su file: restano separati e il body
# e' JSON puro (il -w finisce su stdout), quindi json.tool lo formatta.
# una sola chiamata: /search fa una embedding + una completion.
time_total=$(curl -sS -G "$BASE_URL/search" \
	--data-urlencode "q=$QUERY" \
	-o "$body_file" \
	-D "$headers" \
	-w '%{time_total}')

echo "=== RESPONSE ==="
python3 -m json.tool <"$body_file" 2>/dev/null || cat "$body_file"

printf '\n\033[2m--- status e header ---\033[0m\n'
sed -n '1,/^\r*$/p' "$headers" | sed '/^\r*$/d' | head -20
printf '\033[2mtempo totale: %ss\033[0m\n' "$time_total"
