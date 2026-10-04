#!/usr/bin/env bash
#
# Chiama GET /agent/search (ricerca agentica: il LLM usa il tool di ricerca) con vari scenari
# e per ognuno mostra request, response (answer, steps del tool, sources) e timing.
#
#   ./scripts/agent-search.sh                 # tutti gli scenari
#   ./scripts/agent-search.sh "domanda"       # singola domanda libera, senza filtri
#   BASE_URL=http://host:8080 ./scripts/agent-search.sh
#   PAUSE=20 ./scripts/agent-search.sh        # secondi tra scenari (limite TPM del provider LLM)
#
# Gli scenari 7-9 provano la lettura per documento (listDocuments/getDocumentChunks).
# Gli scenari usano i dati di scripts/curl-examples.sh (contentId C-palline, C-200, ...):
# lanciare prima quello script per avere i dati in indice.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PAUSE="${PAUSE:-0}"

code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/" || true)
if [[ "$code" == "000" ]]; then
	printf '\033[31ml app non raggiungibile su %s (mvn spring-boot:run)\033[0m\n' "$BASE_URL" >&2
	exit 1
fi

# call "titolo" "domanda" [nome=valore ...]  -> parametri extra (langId, contentId)
call() {
	local title="$1" q="$2"
	shift 2
	sleep "$PAUSE"
	local args=(--data-urlencode "q=$q") shown="--data-urlencode \"q=$q\"" p
	for p in "$@"; do
		args+=(--data-urlencode "$p")
		shown+=" --data-urlencode \"$p\""
	done

	printf '\n\033[1m######## %s\033[0m\n' "$title"
	echo "=== REQUEST ==="
	printf '\033[2mcurl -G "%s/agent/search" %s\033[0m\n' "$BASE_URL" "$shown"

	local body time_total
	body=$(mktemp)
	time_total=$(curl -sS -G "$BASE_URL/agent/search" "${args[@]}" -o "$body" -w '%{time_total}')

	echo "=== RESPONSE ==="
	python3 -m json.tool <"$body" 2>/dev/null || cat "$body"

	echo "=== RIEPILOGO ==="
	python3 - "$body" <<'PY' 2>/dev/null || true
import json, sys
d = json.load(open(sys.argv[1]))
print("answer :", d["answer"])
print("ricerche del LLM:", len(d["steps"]))
for i, s in enumerate(d["steps"], 1):
    f = {k: v for k, v in s["filters"].items() if v}
    print(f"  {i}. {s['tool']}({s['query']!r}) mode={s['mode']} filtri={f or '-'} -> {s['hits']} risultati")
print("fonti  :", sorted({x["source"] for x in d["sources"]}) or "-")
PY
	printf '\033[2mtempo totale: %ss\033[0m\n' "$time_total"
	rm -f "$body"
}

if [[ $# -gt 0 ]]; then
	call "domanda libera" "$1"
	exit 0
fi

call "1. domanda semplice: una ricerca" \
	"Di che colore sono le palline da tennis?"

call "2. domanda in due parti: ci si aspetta piu' ricerche" \
	"Di che colore sono le palline da tennis e cosa significa l'errore E4521?"

call "3. fuori dalla knowledge base: riformula e poi no-answer" \
	"Dove si trova la Torre Eiffel?"

call "4. contentId giusto: cerca solo in quel contenuto" \
	"Di che colore sono le palline da tennis?" contentId=C-palline

call "5. contentId sbagliato: nessun risultato -> no-answer" \
	"Di che colore sono le palline da tennis?" contentId=C-200

call "6. langId en: solo documenti in inglese" \
	"Di che colore sono le palline da tennis?" langId=en

call "7. di cosa parla un documento: ci si aspetta listDocuments/getDocumentChunks, non la ricerca" \
	"Di cosa parla il documento palline?"

call "8. come sopra, vincolato a un contentId" \
	"Di cosa parla questo documento?" contentId=C-palline

call "9. documento inesistente: deve dire che non c'e'" \
	"Di cosa parla il documento contratto-affitto?"
