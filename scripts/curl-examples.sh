#!/usr/bin/env bash
#
# Esempi curl per demo-ai-es: ingest, chat, verifica indice e ricerca kNN.
#
#   ./scripts/curl-examples.sh              # tutto
#   ./scripts/curl-examples.sh ingest       # solo l'ingest
#   ./scripts/curl-examples.sh search       # solo il RAG (embed -> kNN -> risposta)
#   ./scripts/curl-examples.sh chat         # solo la chat (serve OPENAI_CHAT_API_KEY)
#   ./scripts/curl-examples.sh index        # solo verifica mapping/documenti
#   ./scripts/curl-examples.sh knn          # solo ricerca kNN (serve JINA_API_KEY)
#
# Override: BASE_URL, ELASTIC_URL, INDEX, SOURCE, CONTENT_ID, SAMPLE_FILE, INLINE_TEXT, QUERY

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

BASE_URL="${BASE_URL:-http://localhost:8080}"
ELASTIC_URL="${ELASTIC_URL:-http://localhost:9200}"
INDEX="${INDEX:-chunks}"
SOURCE="${SOURCE:-doc1}"
# SAMPLE_FILE e' relativo alla root del progetto, non alla cwd: altrimenti lo
# script genera il file di esempio dove capita e l'ingest non usa lo stesso testo.
SAMPLE_FILE="${SAMPLE_FILE:-mio_testo.txt}"
[[ "$SAMPLE_FILE" != /* ]] && SAMPLE_FILE="$PROJECT_ROOT/$SAMPLE_FILE"
INLINE_TEXT="${INLINE_TEXT:-Un bel tramonto sulla spiaggia.}"
# contentId dei documenti della demo: obbligatorio, /ingest risponde 400 se manca
CONTENT_ID="${CONTENT_ID:-C-doc1}"
# TRAIN_TEXT e' il documento che rende RAG_QUERY rispondibile: senza, /search
# risponde "il contesto non contiene la risposta" e la demo non dimostra nulla.
TRAIN_TEXT="${TRAIN_TEXT:-Il treno per Napoli parte dalla stazione centrale alle 8:15 del mattino.}"
RAG_SOURCE="${RAG_SOURCE:-viaggi}"
RAG_CONTENT_ID="${RAG_CONTENT_ID:-C-viaggi}"
RAG_QUERY="${RAG_QUERY:-A che ora parte il treno per Napoli?}"
# secondo giro RAG: contenuto arbitrario, non un fatto noto. Se il LLM risponde
# "verdi" la risposta puo' venire SOLO dal chunk appena ingestato: e' la prova
# che il RAG legge davvero l'indice e non sta indovinando.
BALLS_TEXT="${BALLS_TEXT:-Le palline da tennis nel cesto sono verdi e lucide.}"
BALLS_SOURCE="${BALLS_SOURCE:-palline}"
BALLS_CONTENT_ID="${BALLS_CONTENT_ID:-C-palline}"
BALLS_QUERY="${BALLS_QUERY:-Di che colore sono le palline da tennis?}"
# query in francese: serve a mostrare che il vettore e' multilingua (cfr. embed di Jina).
QUERY="${QUERY:-Un beau coucher de soleil sur la plage}"
EMBEDDING_MODEL="${JINA_EMBEDDING_MODEL:-jina-embeddings-v5-omni-small}"

step=0
say() {
	step=$((step + 1))
	printf '\n\033[1m%s %s\033[0m\n' "$step" "$*"
}
note() { printf '\033[2m    %s\033[0m\n' "$*"; }
fail() { printf '\033[31m    %s\033[0m\n' "$*" >&2; exit 1; }

# cerca una chiave nell'env, poi in .env nella root del progetto, poi in .env della cwd
read_key() {
	local name="$1" f value
	if [[ -n "${!name:-}" ]]; then
		printf '%s' "${!name}"
		return
	fi
	for f in "$PROJECT_ROOT/.env" "$PWD/.env"; do
		[[ -f "$f" ]] || continue
		value=$(sed -n "s/^[[:space:]]*\(export[[:space:]]\+\)\{0,1\}${name}[[:space:]]*=[[:space:]]*//p" "$f" |
			head -1 | tr -d '\r' |
			sed -e 's/[[:space:]]*$//' -e "s/^[\"']//" -e "s/[\"']\$//")
		[[ -n "$value" ]] && { printf '%s' "$value"; return; }
	done
}

pretty() { python3 -m json.tool 2>/dev/null || cat; }

# ---------------------------------------------------------------- 1. app up?
do_health() {
	say "app raggiungibile?"
	code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/" || true)
	[[ "$code" == "000" ]] && fail "nessuna risposta da $BASE_URL: l'app e' avviata? (mvn spring-boot:run)"
	note "HTTP $code su $BASE_URL (404/500 qui e' normale: / non e' mappato)"
}

# ------------------------------------------------------------ 2. ingest file
# contentId e' obbligatorio: e' la chiave con cui /search/grouped raggruppa i chunk.
# python3 costruisce il body JSON, cosi' il testo viene escapingato correttamente.
ingest_body() {
	command -v python3 >/dev/null || fail "python3 serve per costruire il body JSON di /ingest"
	SOURCE="$1" CONTENT_ID="$2" TEXT="$3" python3 -c \
		'import json, os; print(json.dumps({"source": os.environ["SOURCE"], "contentId": os.environ["CONTENT_ID"], "text": os.environ["TEXT"]}))'
}

do_ingest() {
	if [[ ! -f "$SAMPLE_FILE" ]]; then
		printf 'Un bel tramonto sulla spiaggia.\nIl gatto dorme sul tappeto.\n' >"$SAMPLE_FILE"
		note "creato $SAMPLE_FILE di esempio"
	fi
	say "POST /ingest da file ($SAMPLE_FILE)"
	note "curl -X POST \"$BASE_URL/ingest\" -H \"Content-Type: application/json\" \\"
	note "     -d '{\"source\": \"$SOURCE\", \"contentId\": \"$CONTENT_ID\", \"text\": \"...\"}'"
	curl -sS -X POST "$BASE_URL/ingest" \
		-H "Content-Type: application/json" \
		--data-binary "$(ingest_body "$SOURCE" "$CONTENT_ID" "$(cat "$SAMPLE_FILE")")" | pretty

	say "POST /ingest con testo inline"
	curl -sS -X POST "$BASE_URL/ingest" \
		-H "Content-Type: application/json" \
		--data-binary "$(ingest_body "$SOURCE" "$CONTENT_ID" "$INLINE_TEXT")" | pretty

	say "POST /ingest del documento che rende rispondibile RAG_QUERY"
	curl -sS -X POST "$BASE_URL/ingest" \
		-H "Content-Type: application/json" \
		--data-binary "$(ingest_body "$RAG_SOURCE" "$RAG_CONTENT_ID" "$TRAIN_TEXT")" | pretty

	say "POST /ingest di un testo arbitrario (prova che il RAG legge l'indice)"
	curl -sS -X POST "$BASE_URL/ingest" \
		-H "Content-Type: application/json" \
		--data-binary "$(ingest_body "$BALLS_SOURCE" "$BALLS_CONTENT_ID" "$BALLS_TEXT")" | pretty

	say "POST /ingest senza contentId -> 400 (obbligatorio per /search/grouped)"
	curl -sS -X POST "$BASE_URL/ingest" \
		-H "Content-Type: application/json" \
		--data-binary "$(ingest_body "$SOURCE" "" "$INLINE_TEXT")" | pretty
}

# ------------------------------------------------- 3. RAG completo (server)
#embed della domanda -> kNN su ES -> contesto -> risposta LLM, tutto dentro l'app.
do_search() {
	say "GET /search: \"$RAG_QUERY\""
	note "curl \"$BASE_URL/search?q=...\""
	curl -sS -G "$BASE_URL/search" --data-urlencode "q=$RAG_QUERY" | pretty
	note "answer = risposta del LLM, sources = top-k chunk recuperati (source, chunkIndex, score)"
	note "sources[0] e' il chunk piu' vicino: se la risposta e' buona, e' quello citato"

	say "GET /search: \"$BALLS_QUERY\" (risposta impossibile da sapere senza l'indice)"
	note "curl \"$BASE_URL/search?q=...\""
	curl -sS -G "$BASE_URL/search" --data-urlencode "q=$BALLS_QUERY" | pretty
	note "deve citare $BALLS_SOURCE: se risponde 'verdi', il testo e' stato recuperato dall'indice"
}

# --------------------------------------------------------------- 3. chat LLM
do_chat() {
	say "GET /chat"
	if [[ -z "$(read_key OPENAI_CHAT_API_KEY)" ]]; then
		note "OPENAI_CHAT_API_KEY non trovata in env ne in .env: aspettati un 401"
	fi
	note "curl \"$BASE_URL/chat?q=ciao\""
	curl -sS -G "$BASE_URL/chat" --data-urlencode "q=$INLINE_TEXT" | pretty
}

# --------------------------------------------------- 4. verifica su Elasticsearch
do_index() {
	say "indice $INDEX: mapping del campo vettoriale"
	curl -sS "$ELASTIC_URL/$INDEX/_mapping" | pretty | sed -n '/embedding/,+6p'
	note "atteso: dense_vector, dims 1024, similarity cosine (cambia se cambi modello)"

	say "conteggio documenti per sorgente"
	curl -sS -H 'Content-Type: application/json' \
		"$ELASTIC_URL/$INDEX/_search?size=0" \
		-d '{"aggs":{"per_source":{"terms":{"field":"source"}}}}' | pretty
	note "i documenti appena scritti non sono visibili subito: ES refresca ogni 1s"
}

# ------------------------------------------------------- 5. embed + ricerca kNN
do_knn() {
	local key query_vector
	key=$(read_key JINA_API_KEY)
	[[ -n "$key" ]] || fail "JINA_API_KEY non trovata in env ne in .env"
	command -v python3 >/dev/null || fail "python3 serve per costruire il query vector"

	say "embed della query con le API Jina (curl puro)"
	note "curl https://api.jina.ai/v1/embeddings -H \"Content-Type: application/json\" \\"
	note "     -H \"Authorization: Bearer \$JINA_API_KEY\" -d '{...\"input\":[{\"text\":\"$QUERY\"}]}'"

	query_vector=$(QUERY="$QUERY" MODEL="$EMBEDDING_MODEL" JINA_API_KEY="$key" python3 - <<-'PY'
		import json, os, urllib.request
		body = json.dumps({
		    "model": os.environ["MODEL"],
		    "normalized": True,
		    "input": [{"text": os.environ["QUERY"]}],
		}).encode()
		req = urllib.request.Request(
		    "https://api.jina.ai/v1/embeddings", data=body, method="POST",
		    headers={"Content-Type": "application/json",
		             "Authorization": "Bearer " + os.environ["JINA_API_KEY"]})
		data = json.load(urllib.request.urlopen(req))["data"][0]["embedding"]
		print(json.dumps(data))
	PY
	) || fail "chiamata a Jina fallita (vedi errore qui sopra)"

	say "kNN su $INDEX con il vettore della query"
	note "POST $ELASTIC_URL/$INDEX/_search {\"knn\":{\"field\":\"embedding\", ...}}"
	curl -sS -H 'Content-Type: application/json' \
		"$ELASTIC_URL/$INDEX/_refresh" -o /dev/null
	curl -sS -H 'Content-Type: application/json' \
		"$ELASTIC_URL/$INDEX/_search?filter_path=hits.hits._id,hits.hits._score,hits.hits._source.content" \
		-d "{\"knn\":{\"field\":\"embedding\",\"query_vector\":$query_vector,\"k\":3,\"num_candidates\":10}}" |
		python3 -m json.tool
	note "i vettori non compaiono in _source (exclude_source_vectors=true su ES 9):"
	note "si verifica con _search + knn, non con GET /_source"
}

case "${1:-all}" in
health) do_health ;;
ingest) do_ingest ;;
search) do_search ;;
chat) do_chat ;;
index) do_index ;;
knn) do_knn ;;
all)
	do_health
	do_ingest
	do_index
	do_search
	do_knn
	do_chat
	;;
*) fail "uso: $0 [health|ingest|search|chat|index|knn|all]" ;;
esac
