#!/usr/bin/env bash
#
# Curl pronti per ingest con metadati e ricerche con/senza filtri.
# Si puo' lanciare tutto (./scripts/curl-metadata.sh) oppure copiare il singolo curl.
#
# Override: BASE_URL

BASE_URL="${BASE_URL:-http://localhost:8080}"

h() { printf '\n\033[1m# %s\033[0m\n' "$*"; }

# ============================================================== INGEST

h "ingest JSON con tutti i metadati (manuale italiano)"
curl -s -X POST "$BASE_URL/ingest" -H "Content-Type: application/json" -d '{
  "source": "manuale-it",
  "text": "Il codice errore E4521 indica che il filtro della lavatrice è intasato: pulirlo sotto acqua corrente. Il codice E3002 indica che la porta non è chiusa bene.",
  "langId": "it",
  "contentId": "C-100",
  "topics": ["lavatrice", "errori"],
  "filename": "manuale_lavatrice_it.pdf"
}'

h "ingest JSON con tutti i metadati (stesso manuale in inglese)"
curl -s -X POST "$BASE_URL/ingest" -H "Content-Type: application/json" -d '{
  "source": "manuale-en",
  "text": "Error code E4521 means the washing machine filter is clogged: rinse it under running water. Error code E3002 means the door is not properly closed.",
  "langId": "en",
  "contentId": "C-101",
  "topics": ["washing-machine", "errors"],
  "filename": "washer_manual_en.pdf"
}'

h "ingest JSON, un solo topic"
curl -s -X POST "$BASE_URL/ingest" -H "Content-Type: application/json" -d '{
  "source": "ricette",
  "text": "La carbonara si prepara con guanciale, uova, pecorino e pepe nero, senza panna. La amatriciana usa guanciale, pomodoro e pecorino.",
  "langId": "it",
  "contentId": "C-200",
  "topics": ["cucina"],
  "filename": "ricette.txt"
}'

h "ingest JSON con solo i campi obbligatori (source + text, niente metadati)"
curl -s -X POST "$BASE_URL/ingest" -H "Content-Type: application/json" -d '{
  "source": "viaggi",
  "text": "Il treno per Napoli parte dalla stazione centrale alle 8:15 del mattino."
}'

h "ingest text/plain (vecchio formato: source in query string, niente metadati)"
curl -s -X POST "$BASE_URL/ingest?source=palline" -H "Content-Type: text/plain" \
     --data-binary "Le palline da tennis nel cesto sono verdi e lucide."

h "ingest text/plain da file (crea prima il file, es. echo 'testo' > mio_testo.txt)"
[[ -f mio_testo.txt ]] && curl -s -X POST "$BASE_URL/ingest?source=doc1" -H "Content-Type: text/plain" \
     --data-binary "@mio_testo.txt"

h "ingest senza source -> 400"
curl -s -o /dev/null -w 'HTTP %{http_code}' -X POST "$BASE_URL/ingest" \
     -H "Content-Type: application/json" -d '{"text": "manca source"}'

# ============================================================== SEARCH SEMANTICA

h "semantica, senza filtri"
curl -s -G "$BASE_URL/search" --data-urlencode "q=cosa significa l'errore E4521?"

h "semantica, domanda in francese senza filtri (il vettore e' multilingua)"
curl -s -G "$BASE_URL/search" --data-urlencode "q=que signifie le code d'erreur E4521 ?"

h "semantica, filtro langId=en"
curl -s -G "$BASE_URL/search" --data-urlencode "q=cosa significa l'errore E4521?" \
     -d langId=en

h "semantica, filtro topic=cucina"
curl -s -G "$BASE_URL/search" --data-urlencode "q=come si fa la carbonara?" \
     -d topic=cucina

h "semantica, piu' topic (OR: basta uno dei due)"
curl -s -G "$BASE_URL/search" --data-urlencode "q=cosa significa l'errore E4521?" \
     -d topic=errori -d topic=errors

h "semantica, filtri combinati (AND tra campi): langId + contentId + filename"
curl -s -G "$BASE_URL/search" --data-urlencode "q=cosa significa l'errore E3002?" \
     -d langId=it -d contentId=C-100 -d filename=manuale_lavatrice_it.pdf

h "semantica, filtro source"
curl -s -G "$BASE_URL/search" --data-urlencode "q=a che ora parte il treno per Napoli?" \
     -d source=viaggi

h "semantica, filtro che esclude la risposta -> no-answer"
curl -s -G "$BASE_URL/search" --data-urlencode "q=cosa significa l'errore E4521?" \
     -d contentId=C-200

h "semantica, domanda senza risposta nell'indice -> no-answer"
curl -s -G "$BASE_URL/search" --data-urlencode "q=dove si trova la Torre Eiffel?"

# ============================================================== SEARCH IBRIDA

h "ibrida, senza filtri"
curl -s -G "$BASE_URL/search" --data-urlencode "q=cosa significa l'errore E4521?" \
     -d mode=hybrid

h "ibrida, filtro langId=it"
curl -s -G "$BASE_URL/search" --data-urlencode "q=codice E3002" \
     -d mode=hybrid -d langId=it

h "ibrida, piu' topic"
curl -s -G "$BASE_URL/search" --data-urlencode "q=cosa significa l'errore E4521?" \
     -d mode=hybrid -d topic=errori -d topic=cucina

h "ibrida, tutti i filtri insieme"
curl -s -G "$BASE_URL/search" --data-urlencode "q=error E4521" \
     -d mode=hybrid -d source=manuale-en -d langId=en -d contentId=C-101 \
     -d topic=errors -d filename=washer_manual_en.pdf

h "ibrida, filtro che esclude la risposta -> no-answer"
curl -s -G "$BASE_URL/search" --data-urlencode "q=cosa significa l'errore E4521?" \
     -d mode=hybrid -d topic=cucina

h "mode non valido -> 400"
curl -s -o /dev/null -w 'HTTP %{http_code}' -G "$BASE_URL/search" -d q=test -d mode=foo

echo
