#!/usr/bin/env bash
#
# Verifica end-to-end di GET /agent/chat, da lanciare con l'app attiva.
# Per ogni caso stampa la richiesta (la riga curl) e la risposta.
#
# Prerequisito: OPENAI_CHAT_API_KEY (serve il modello che decide cosa cercare) e i documenti
# di scripts/curl-metadata.sh gia' indicizzati.
#
# Override: BASE_URL

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

h() { printf '\n\033[1m# %s\033[0m\n' "$*"; }

# Una chiamata con richiesta e risposta. La richiesta e' la riga curl, non un body:
# /agent/chat e' una GET con la domanda in un query param, quindi la riga dice tutto
# quello che va detto. La risposta si formatta come in search.sh, e in un agente la
# risposta da sola non basta: quello che conta e' anche toolCalls, cioe' quali ricerche
# ha deciso di fare il modello.
ask() {
	local endpoint="$1" query="$2"
	printf '\n\033[2mcurl -G "%s%s" --data-urlencode "q=%s"\033[0m\n' "$BASE_URL" "$endpoint" "$query"
	local body
	body=$(mktemp)
	if ! curl -sS -G "$BASE_URL$endpoint" --data-urlencode "q=$query" -o "$body"; then
		printf '\033[31mchiamata fallita\033[0m\n'
		rm -f "$body"
		return 1
	fi
	# json.tool sul file, non in pipe: se il body non e' JSON valido la pipe lo consumerebbe
	# e si perderebbe la risposta da mostrare
	python3 -m json.tool <"$body" 2>/dev/null || cat "$body"
	rm -f "$body"
}

if [[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/" || true)" == "000" ]]; then
	printf '\033[31ml app non raggiungibile su %s (mvn spring-boot:run)\033[0m\n' "$BASE_URL" >&2
	exit 1
fi

h "una domanda: l'agente riscrive la query, cerca e cita la fonte"
ask /agent/chat "cosa significa l'errore E4521 e come si rimuove il filtro?"

h "confronto fra due documenti: due ricerche con langId diversi, decise dal modello"
ask /agent/chat "confronta cosa dice il manuale italiano e quello inglese sull'errore E4521"

h "codice esatto: il modello sceglie mode=lexical da solo"
ask /agent/chat "E4521"

h "domanda fuori indice: il tool non trova chunk, l'agente lo dice e non inventa"
ask /agent/chat "Chi ha vinto il campionato di calcio del 1994?"

h "stessa domanda su /chat, senza RAG e senza tool: il modello risponde di memoria"
ask /chat "Chi ha vinto il campionato di calcio del 1994?"

echo
