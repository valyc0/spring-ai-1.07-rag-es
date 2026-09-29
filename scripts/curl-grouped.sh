#!/usr/bin/env bash
#
# Verifica end-to-end di GET /search/grouped, da lanciare con l'app attiva.
# Prerequisito: i documenti di scripts/curl-metadata.sh gia' indicizzati.
#
# Override: BASE_URL

BASE_URL="${BASE_URL:-http://localhost:8080}"

h() { printf '\n\033[1m# %s\033[0m\n' "$*"; }

h "grouped semantica, senza filtri: un gruppo per contentId, per score decrescente"
curl -s -G "$BASE_URL/search/grouped" --data-urlencode "q=cosa significa l'errore E4521?"

h "grouped semantica, filtro langId=en: resta solo il manuale inglese"
curl -s -G "$BASE_URL/search/grouped" --data-urlencode "q=error E4521" -d langId=en

h "grouped ibrida: knnScore e bm25Score valorizzati nei chunk"
curl -s -G "$BASE_URL/search/grouped" --data-urlencode "q=error E4521" -d mode=hybrid

h "grouped lessicale: solo BM25, knnScore null, un chunk per contentId, senza embedding"
curl -s -G "$BASE_URL/search/grouped" --data-urlencode "q=error E4521" -d mode=lexical

h "lessicale senza match -> no-answer, l'LLM non viene chiamato"
curl -s -G "$BASE_URL/search/grouped" --data-urlencode "q=xyzzy plugh frobnicate" -d mode=lexical

h "grouped con risposta LLM per ogni contentId"
curl -s -G "$BASE_URL/search/grouped" --data-urlencode "q=cosa significa l'errore E4521?" -d answer=true

h "grouped con filtro che esclude tutto -> noAnswer true, groups vuota"
curl -s -G "$BASE_URL/search/grouped" --data-urlencode "q=cosa significa l'errore E4521?" -d contentId=C-999

h "grouped mode non valido -> 400"
curl -s -o /dev/null -w 'HTTP %{http_code}' -G "$BASE_URL/search/grouped" -d q=test -d mode=foo

echo
