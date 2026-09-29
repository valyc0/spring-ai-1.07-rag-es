# `/search/grouped` — risultati raggruppati per `contentId`

## Obiettivo

Data una query `q` + filtri opzionali sui metadati, restituire **per ogni `contentId` il chunk
con lo score più alto**, con i gruppi ordinati per score decrescente. Opzionalmente, una risposta
LLM **per documento** (per `contentId`).

Serve a un caso d'uso diverso da `/search`: `/search` risponde a "quali chunk parlano di X" e
passa tutto a un unico prompt; `/search/grouped` risponde a "quali *documenti* parlano di X, e
ognuno cosa dice", con confronto tra documenti diversi.

Per ora **solo API**: nessuna UI. La pagina (statica o altro) eventualmente dopo, e userà questo
endpoint.

## Perché non basta `/search`

`/search` restituisce al massimo `top-k` (=5) chunk piatti, senza deduplica per `contentId`. Per
raggruppare serve un pool di candidati più largo: altrimenti, con pochi `contentId` distinti
nell'indice, non si vedrebbero tutti i documenti rilevanti. Quindi `/search/grouped` prende
molti candidati (`group-window`), poi raggruppa.

## Flusso

1. **Embed** della query (stesso modello dell'ingest: vettori confrontabili).
2. **Recupero candidati** con `limit = group-window` (default 50), riusando la pipeline attuale:
   - `semantic`: solo kNN, `k = group-window`;
   - `hybrid`: kNN + BM25 fusi con RRF, con la stessa logica di oggi.
3. **Filtri** applicati dentro le query come oggi (`knn.filter`, `bool.filter`), non in
   `post_filter`.
4. **Raggruppa per `contentId`**, tenendo il chunk con score più alto per gruppo. Chunk con
   `contentId` nullo finiscono in un gruppo con chiave `null` (in pratica: ingest senza metadati).
5. **Soglia**: si applica `min-score` al **miglior chunk di ogni gruppo**. Un gruppo il cui top è
   sotto soglia non compare.
6. **Ordina** i gruppi per score decrescente, taglia a `max-groups`.
7. Se **nessun** gruppo passa la soglia → `no-answer`: risposta fissa, lista vuota, nessuna
   chiamata LLM (come `/search`).
8. Se `answer=true`, per ogni gruppo chiama l'LLM con il contesto dei migliori `chunks-per-group`
   chunk *di quel documento*, producendo una risposta per `contentId`. Se `answer=false`
   (default) il campo `answer` dei gruppi è `null` e non si chiama l'LLM.

### Dettaglio raggruppamento e soglia

Il raggruppamento avviene sullo stesso elenco ordinato per score prodotto dal retrieval
(kNN in `semantic`, RRF in `hybrid`). Come in `/search`, la soglia guarda **sempre lo score
kNN**, anche in `hybrid`: lo score RRF dipende solo dalle posizioni e non dice se il chunk parla
davvero della domanda. Quindi:

- in `semantic` lo "score" del chunk è il kNN;
- in `hybrid` lo "score" per l'ordinamento è l'RRF, ma il valore di soglia è il `knnScore`.

Per coerenza con `/search`, se **il punteggio kNN più alto in assoluto** è sotto `min-score`, si
risponde `no-answer` subito (nessun gruppo). Attenzione: in `hybrid` l'ordine dei risultati è
l'RRF, quindi "primo della lista fusa" **non** è necessariamente il kNN più alto. La soglia va
quindi valutata sul **massimo `knnScore`** tra i candidati, non sul primo elemento fuso. Per
questo `retrieve` espone anche il top kNN (vedi sotto), e il controllo è:

```
knnTop < minScore  ->  no-answer, nessun gruppo
```

## Refactor

Estrarre dal `SearchService` attuale un metodo privato riutilizzabile che restituisca sia i hit
kNN sia la lista fusa ordinata:

```java
private record Retrieval(float knnTop, List<ChunkHit> ranked) {}

private Retrieval retrieve(String question, SearchMode mode, SearchFilters filters, int limit)
```

`Retrieval.knnTop` = score kNN del miglior vicino (0 se vuoto), per il controllo di soglia
condiviso. `ranked` = `ChunkHit` ordinati (kNN in `semantic`, RRF in `hybrid`) fino a `limit`.

- `search(...)` chiama `retrieve(..., topK)` e mantiene il comportamento attuale identico:
  stesso controllo `knnTop < minScore`, stesso contesto, stessa risposta.
- `searchGrouped(...)` chiama `retrieve(..., group-window)`, poi raggruppa.

Un unico helper privato `underThreshold(float knnTop)` o un semplice `if` ripetuto entrambi
accettabili; nel piano si sceglie l'helper per evitare duplicazione.

## Config nuova

```yaml
app:
  search:
    grouped:
      group-window: 50    # candidati da cui pescare i contentId (<= num-candidates)
      max-groups: 10      # max contentId restituiti
      chunks-per-group: 2 # chunk del documento usati come contesto LLM (solo con answer=true)
```

Vincoli di validazione nel costruttore (stile di quelli esistenti):
- `group-window >= 1`;
- `group-window <= num-candidates` (il kNN non può chiedere più di `num-candidates`);
- `max-groups >= 1`;
- `chunks-per-group >= 1`.

`max-groups` può superare il numero di contentId presenti: verranno restituiti quelli disponibili.
`chunks-per-group` = 1 è il caso "solo il chunk migliore"; con 2+ il contesto LLM include i chunk
successivi dello stesso documento.

## Endpoint

```
GET /search/grouped
  ?q=...                 (obbligatorio)
  &mode=semantic|hybrid  (default semantic)
  &answer=true|false     (default false)
  &source=... &langId=... &contentId=... &topic=... &filename=...   (stessi filtri di /search)
```

`mode` non valido → `400` (come `/search`). `q` mancante → `400` (gestito da Spring).

### Risposta

```json
{
  "query": "errore E4521",
  "mode": "semantic",
  "groups": [
    {
      "contentId": "C-100",
      "source": "manuale-it",
      "langId": "it",
      "topics": ["lavatrice", "errori"],
      "filename": "manuale_lavatrice_it.pdf",
      "score": 0.83,
      "chunks": [
        {"chunkIndex": 0, "content": "...", "score": 0.83, "knnScore": 0.83, "bm25Score": null}
      ],
      "answer": "Il codice E4521 indica che il filtro..."
    }
  ],
  "chunksUsed": 3,
  "noAnswer": false
}
```

- `score` del gruppo = score del suo chunk migliore (kNN in `semantic`, RRF in `hybrid`).
- `chunks`: i migliori `chunks-per-group` chunk del documento, nell'ordine di ranking; con
  `answer=false` comunque presenti (servono a mostrare le fonti).
- `answer`: `null` se `answer=false`, altrimenti la risposta LLM sul solo documento del gruppo.
- `noAnswer`: `true` quando nessun gruppo passa la soglia; `groups` vuota e `answer` assente.
- `chunksUsed`: numero totale di chunk nei gruppi restituiti.

### Nuovi tipi

- `GroupedSearchResult` (record): `query, mode, groups, chunksUsed, noAnswer`.
- `GroupHit` (record): `contentId, source, langId, topics, filename, score, chunks, answer`.
- `ChunkHit` resta quello esistente (già con `knnScore`/`bm25Score`). Si può riusare tal quale.

## Error handling

- `mode` non valido → `400 Bad Request`.
- `answer=true` ma ChatClient non configurato (nessuna API key): oggi `/search` manderebbe in
  errore il LLM; non aggiungiamo gestione speciale in questa iterazione (comportamento coerente
  con `/search`).
- LLM che fallisce su un gruppo: l'eccezione propaga (come in `/search`). Nessuna risposta
  parziale per ora (YAGNI).

## Testing

Il progetto non ha test al momento (`src/test` assente, solo `spring-boot-starter-test` in pom).
Verifica manuale via curl, seguendo lo stile di `scripts/curl-metadata.sh`:

1. ingest di 2+ documenti con `contentId` diversi e topic diversi (già in
   `scripts/curl-metadata.sh`);
2. `/search/grouped?q=errore E4521` → un gruppo per `contentId`, ordinati per score;
3. filtro `langId=it` → solo il gruppo del manuale italiano;
4. `answer=true` → un campo `answer` per gruppo;
5. filtro che esclude tutto → `noAnswer: true`, `groups: []`;
6. `mode=hybrid` → stessi gruppi, con `knnScore`/`bm25Score` valorizzati;
7. `mode=foo` → `400`.

Si aggiungerà uno script `scripts/curl-grouped.sh` (o una sezione in `curl-metadata.sh`) con
questi curl.

## Fuori scope

- UI / pagina web.
- Risposte LLM in parallelo (con `answer=true` le chiamate sono sequenziali: N gruppi = N
  chiamate; accettabile per la demo, da ottimizzare eventualmente dopo).
- Deduplica/paginazione oltre `max-groups`.
- Gestione parziale degli errori LLM per gruppo.