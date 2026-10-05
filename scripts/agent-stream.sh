#!/usr/bin/env bash
#
# Chiama GET /agent/search/stream (SSE) e stampa gli eventi man mano che arrivano,
# ognuno con il tempo di arrivo: si vede che gli step dei tool precedono i token.
#
#   ./scripts/agent-stream.sh                          # domanda di default
#   ./scripts/agent-stream.sh "chi ha scritto il libro?"
#   CONTENT_ID=C-palline ./scripts/agent-stream.sh     # vincola a un contentId (anche LANG_ID)
#   BASE_URL=http://host:8080 ./scripts/agent-stream.sh
#
# Eventi: step (chiamata a un tool), token (pezzo di risposta), replace (nessun chunk trovato:
# sostituire il testo con no-answer), done (fonti, truncated), error.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
QUERY="${1:-${QUERY:-Di che colore sono le palline da tennis?}}"

args=(--data-urlencode "q=$QUERY")
[[ -n "${LANG_ID:-}" ]] && args+=(--data-urlencode "langId=$LANG_ID")
[[ -n "${CONTENT_ID:-}" ]] && args+=(--data-urlencode "contentId=$CONTENT_ID")

echo "=== REQUEST ==="
printf '\033[2mcurl -N -G "%s/agent/search/stream" %s\033[0m\n\n' "$BASE_URL" "${args[*]}"

code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/" || true)
if [[ "$code" == "000" ]]; then
	printf '\033[31ml app non raggiungibile su %s (mvn spring-boot:run)\033[0m\n' "$BASE_URL" >&2
	exit 1
fi

echo "=== STREAM (secondi dall'inizio | evento | dato) ==="
start=$(date +%s.%N)
event=""
answer=""
# -N disabilita il buffering di curl: gli eventi arrivano appena il server li manda
curl -sN -G "$BASE_URL/agent/search/stream" "${args[@]}" | while IFS= read -r line; do
	line="${line%$'\r'}"
	case "$line" in
	event:*) event="${line#event:}" ;;
	data:*)
		data="${line#data:}"
		t=$(printf '%.2f' "$(echo "$(date +%s.%N) - $start" | bc)")
		if [[ "$event" == "token" ]]; then
			printf '\033[2m%6ss token\033[0m  %s\n' "$t" "$data"
			answer+="$data"
		else
			printf '%6ss \033[1m%-7s\033[0m %s\n' "$t" "$event" "$data"
		fi
		;;
	esac
done
