#!/usr/bin/env bash
#
# Pulisce l'indice dei chunk.
#
#   ./scripts/clear-index.sh              # svuota i documenti, TUTTO MENO il mapping
#   ./scripts/clear-index.sh --drop       # elimina anche l'indice (il mapping si ricrea da solo)
#   ./scripts/clear-index.sh --yes        # non chiede conferma
#
# Override: ELASTIC_URL, INDEX

set -euo pipefail

ELASTIC_URL="${ELASTIC_URL:-http://localhost:9200}"
INDEX="${INDEX:-chunks}"

drop=0
assume_yes=0
for arg in "$@"; do
	case "$arg" in
	--drop) drop=1 ;;
	--yes | -y) assume_yes=1 ;;
	-h | --help)
		sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
		exit 0
		;;
	*) printf 'opzione sconosciuta: %s\n' "$arg" >&2; exit 1 ;;
	esac
done

fail() { printf '\033[31m    %s\033[0m\n' "$*" >&2; exit 1; }
note() { printf '\033[2m    %s\033[0m\n' "$*"; }

code=$(curl -s -o /dev/null -w '%{http_code}' "$ELASTIC_URL" || true)
[[ "$code" == "000" ]] && fail "Elasticsearch non raggiungibile su $ELASTIC_URL"

exists=$(curl -s -o /dev/null -w '%{http_code}' "$ELASTIC_URL/$INDEX")
if [[ "$exists" == "404" ]]; then
	note "l'indice $INDEX non esiste: niente da fare"
	exit 0
fi

before=$(curl -s "$ELASTIC_URL/$INDEX/_count" | sed -n 's/.*"count":\([0-9]*\).*/\1/p')

if [[ "$before" == "0" && "$drop" == "0" ]]; then
	note "l'indice $INDEX e' gia' vuoto: niente da fare"
	exit 0
fi

printf "l'indice %s contiene %s chunk\n" "$INDEX" "$before"

if [[ "$assume_yes" == "0" ]]; then
	if [[ "$drop" == "1" ]]; then
		printf '\033[33mserve ELIMINARE l\u0027indice %s (mapping incluso).\033[0m\n' "$INDEX"
		printf 'I chunk non si possono recuperare. Continuare? [s/N] '
	else
		printf 'Eliminare tutti i chunk (il mapping resta)? [s/N] '
	fi
	# senza stdin (crono, CI, pipe) read va in EOF: meglio fermarsi che
	# interpretare un input assente come un "s'" implicito.
	if ! read -r reply; then
		note "nessun input: usa --yes per confermare"
		exit 1
	fi
	case "$reply" in
	s | S | y | Y | si | yes) ;;
	*) note "annullato"; exit 0 ;;
	esac
fi

if [[ "$drop" == "1" ]]; then
	# niente ?refresh=true qui: DELETE su un indice non lo accetta (ES 9 -> 400
	# "unrecognized parameter"), e non serve: rimuovendo l'indice sparisce tutto.
	curl -sS -X DELETE "$ELASTIC_URL/$INDEX"
	printf '\nindice %s eliminato (mapping incluso)\n' "$INDEX"
	# ChunkDocument ha createIndex=false: se l'indice lo crea il primo save(), ES usa il
	# mapping dinamico (source diventa text, non keyword). Quello giusto lo crea
	# ElasticIndexInitializer, che gira solo all'avvio dell'app.
	note "riavvia l'app prima del prossimo /ingest: ElasticIndexInitializer ricrea"
	note "l'indice col mapping corretto (source keyword, dense_vector 1024 cosine)"
else
	# _delete_by_query mantiene il mapping: l'app continua a funzionare senza restart.
	curl -sS -X POST "$ELASTIC_URL/$INDEX/_delete_by_query?refresh=true" \
		-H 'Content-Type: application/json' \
		-d '{"query":{"match_all":{}}}'
	printf '\n%d chunk eliminati, mapping intatto\n' "$before"
	note "l'app puo' restare su: /ingest e /search continuano a funzionare"
fi
