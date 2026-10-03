#!/usr/bin/env bash
#
# Verifica end-to-end dei filtri opzionali di GET /agent/chat: per ogni caso stampa la
# richiesta (la riga curl), la risposta e un verdetto. Il verdetto e' la parte interessante:
# non basta vedere che la risposta arriva, bisogna vedere che ogni ricerca che il modello ha
# deciso di fare e' finita con i filtri della richiesta. E' la differenza fra "il filtro e'
# stato applicato" e "il filtro e' finito nella risposta".
#
#   ./scripts/curl-agent-filters.sh
#   BASE_URL=http://host:8080 ./scripts/curl-agent-filters.sh
#   PAUSE_SECONDS=10 ./scripts/curl-agent-filters.sh    # provider con rate limit stretto
#
# Prerequisito: app attiva, OPENAI_CHAT_API_KEY e i documenti di scripts/curl-metadata.sh
# gia' indicizzati.
#
# Override: BASE_URL, PAUSE_SECONDS

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PAUSE_SECONDS="${PAUSE_SECONDS:-6}"

h() { printf '\n\033[1m# %s\033[0m\n' "$*"; }
FAILURES=0
BODY=""
HTTP_CODE=""
trap 'rm -f "${BODY:-}"' EXIT

# Una chiamata: stampa la riga curl (la richiesta) e il body formattato, e lascia entrambi in
# $BODY e $HTTP_CODE per i controlli, che leggono la stessa risposta appena mostrata. Su
# /agent/chat la richiesta e' una GET con la domanda in un query param: la riga curl dice
# tutto, quindi non c'e' un body da mostrare. Gli argomenti in piu' sono i filtri opzionali.
ask() {
	local endpoint="$1" query="$2"
	shift 2
	BODY=$(mktemp)
	printf '\n\033[2mcurl -G "%s%s" --data-urlencode "q=%s" %s\033[0m\n' "$BASE_URL" "$endpoint" "$query" "$*"
	# -w su stdout, body su file: restano separati e json.tool formatta il file
	HTTP_CODE=$(curl -sS -G "$BASE_URL$endpoint" --data-urlencode "q=$query" "$@" \
		-o "$BODY" -w '%{http_code}' || printf '000')
	# json.tool sul file, non in pipe: se il body non e' JSON valido la pipe lo consumerebbe
	# e si perderebbe la risposta da mostrare
	python3 -m json.tool <"$BODY" 2>/dev/null || cat "$BODY"
	[[ "$HTTP_CODE" == 2* ]] || printf '   \033[31mKO\033[0m HTTP %s\n' "$HTTP_CODE"
	return 0
}

# Se la chiamata e' andata storta (rete, o 429 del provider quando le chiamate di fila
# esauriscono i token al minuto) i controlli non hanno niente da guardare: dirlo una volta e
# non aggiungere tre KO fuorvianti.
richiesta_ok() {
	if [[ "$HTTP_CODE" == 2* ]]; then
		return 0
	fi
	printf "   \033[31mKO\033[0m controlli saltati: la richiesta non e' andata (HTTP %s)\n" "$HTTP_CODE"
	FAILURES=$((FAILURES + 1))
	return 1
}

# Ogni ricerca che il modello ha fatto deve essere finita con il filtro atteso: e' la prova che
# la precedenza e' applicata nel tool e non e' solo una riga nel prompt.
check_filters() {
	local needle="$1"
	richiesta_ok || return
	python3 - "$BODY" "$needle" <<'PY' || FAILURES=$((FAILURES + 1))
import json, sys
body, needle = sys.argv[1], sys.argv[2]
data = json.load(open(body, encoding="utf-8"))
calls = data.get("toolCalls") or []
if not calls:
    print("   \033[31mKO\033[0m nessuna ricerca: il tool non e' stato chiamato")
    sys.exit(1)
mancanti = [c for c in calls if needle not in (c.get("filters") or "")]
if mancanti:
    print("   \033[31mKO\033[0m %s assente in: %s" % (needle, ", ".join(c.get("filters", "-") for c in mancanti)))
    sys.exit(1)
print("   \033[32mOK\033[0m %d ricerche, tutte con %s" % (len(calls), needle))
PY
}

# Quanti chunk porta via ogni ricerca. Il tetto e' per ricerca, non la somma: con piu'
# ricerche la somma cresce e il controllo direbbe " KO" mentre il filtro ha funzionato. Serve
# perche' i chunk sono il conto che si vede: il manuale italiano ne ha 2, quindi una ricerca
# che ne torna 1 dimostra che l'altro e' stato escluso.
check_max_chunks() {
	local tetto="$1"
	richiesta_ok || return
	python3 - "$BODY" "$tetto" <<'PY' || FAILURES=$((FAILURES + 1))
import json, sys
body, tetto = sys.argv[1], int(sys.argv[2])
data = json.load(open(body, encoding="utf-8"))
calls = data.get("toolCalls") or []
if not calls:
    print("   \033[31mKO\033[0m nessuna ricerca: il tool non e' stato chiamato")
    sys.exit(1)
peggiori = [c["chunks"] for c in calls if c.get("chunks", 0) > tetto]
if peggiori:
    print("   \033[31mKO\033[0m ricerche con %s chunk, tetto %d" % (peggiori, tetto))
    sys.exit(1)
print("   \033[32mOK\033[0m %d ricerche, al massimo %d chunk ciascuna" % (len(calls), tetto))
PY
}

# Il tool ha risposto "non trovato" e l'agente non ha risposto a memoria: un filtro che
# esclude tutto non lascia niente su cui inventare.
check_non_trovato() {
	richiesta_ok || return
	python3 - "$BODY" <<'PY' || FAILURES=$((FAILURES + 1))
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
calls = data.get("toolCalls") or []
if not calls or any(c.get("chunks", 0) for c in calls):
    print("   \033[31mKO\033[0m il tool ha trovato qualcosa: il filtro non ha escluso")
    sys.exit(1)
print("   \033[32mOK\033[0m %d ricerche, 0 chunk: il tool ha risposto 'non trovato'" % len(calls))
PY
}

# Tra un caso e l'altro l'agente costa N+1 chiamate al modello: senza pausa si esaurisce il
# token-per-minute del provider e la richiesta torna 429, che non e' un difetto dei filtri.
pausa() { sleep "$PAUSE_SECONDS"; }

if [[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/" || true)" == "000" ]]; then
	printf '\033[31ml app non raggiungibile su %s (mvn spring-boot:run)\033[0m\n' "$BASE_URL" >&2
	exit 1
fi

h "senza filtri: la richiesta non impone niente, il langId lo sceglie il modello"
ask /agent/chat "cosa dice il manuale italiano sull'errore E4521?"
# nessun valore atteso: quale lingua sia e' una scelta del modello, il controllo e' che
# abbia filtrato per una lingua (filters contiene langId=...), non quale
check_filters "langId="
check_max_chunks 2
pausa

h "langId dalla richiesta: il modello avrebbe cercato in italiano, trova in inglese"
ask /agent/chat "cosa dice il manuale italiano sull'errore E4521?" -d langId=en
check_filters "langId=en"
# il manuale italiano ha 2 chunk: tornandone 1 solo, e' stato escluso
check_max_chunks 1
pausa

h "contentId dalla richiesta: il resto dell'indice e' scartato"
ask /agent/chat "cosa dice sull'errore E4521?" -d contentId=C-101
check_filters "contentId=C-101"
check_max_chunks 1
pausa

h "piu' filtri insieme: AND come su /search, e la trace li mostra tutti"
ask /agent/chat "cosa dice sull'errore E4521?" -d langId=en -d contentId=C-101 -d filename=washer_manual_en.pdf
check_filters "langId=en, contentId=C-101, filename=washer_manual_en.pdf"
check_max_chunks 1
pausa

h "topic dalla richiesta: vince la lista della richiesta, non quella del modello"
ask /agent/chat "cosa dice sull'errore E4521?" -d topic=errori
check_filters "topics=[errori]"
pausa

h "source dalla richiesta"
ask /agent/chat "cosa dice sull'errore E4521?" -d source=manuale-en
check_filters "source=manuale-en"
pausa

h "filtro che esclude tutto: 0 chunk, l'agente lo dice e non risponde a memoria"
ask /agent/chat "cosa dice sull'errore E4521?" -d contentId=C-999
check_filters "contentId=C-999"
check_non_trovato
pausa

h "stessi filtri su /search, che non ha tool: il confronto torna"
curl -s -G "$BASE_URL/search" --data-urlencode "q=errore E4521" -d langId=en -d contentId=C-101 \
	| python3 -m json.tool 2>/dev/null || true

printf '\n'
if [[ "$FAILURES" -eq 0 ]]; then
	printf '\033[32mfiltri dalla richiesta: tutti i controlli passati\033[0m\n'
else
	printf '\033[31mfiltri dalla richiesta: %s controlli falliti\033[0m\n' "$FAILURES"
	exit 1
fi
