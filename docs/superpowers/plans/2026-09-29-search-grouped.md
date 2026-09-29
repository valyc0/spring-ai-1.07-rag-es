# /search/grouped Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aggiungere `GET /search/grouped`, che per ogni `contentId` restituisce il chunk con lo score più alto (gruppi ordinati per score decrescente) e, opzionalmente, una risposta LLM per documento.

**Architecture:** Si estrae dal `SearchService` la pipeline di retrieval in un metodo `retrieve(...)` riutilizzabile (embed → kNN [+ BM25] → fusione), condiviso tra `/search` (limit = `top-k`) e `/search/grouped` (limit = `group-window`). Il raggruppamento per `contentId` è una funzione pura, testabile senza Spring/Elasticsearch. L'endpoint riusa gli stessi filtri di `/search`.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring AI 1.0.7, spring-data-elasticsearch, JUnit 5 + AssertJ (già da `spring-boot-starter-test`).

## Global Constraints

- Java 17, nessuna nuova dipendenza in `pom.xml`.
- I commenti nel codice esistente sono in italiano, senza accenti: mantenere lo stile.
- Gli score kNN sono in `0-1` (`(1+cos)/2`), `min-score` default `0.76`.
- `/search` esistente **non deve cambiare comportamento** (stessi hit, stesso ordine, stessa soglia).
- I record di risposta vivono annidati in `SearchService`, come `ChunkHit`/`SearchResult` esistenti.
- Nessuna UI, nessun test di integrazione con ES: la funzione di raggruppamento si unit-testa, il resto si verifica con curl.

---

## File Structure

- `src/main/resources/application.yml` — aggiunge il blocco `app.search.grouped.*`.
- `src/main/java/com/example/demo/SearchService.java` — config nuovi campi + validazione; metodo `retrieve`; `fuse`/`bm25` parametrici sul limit; funzione statica `group`; metodo `searchGrouped`; `answerFor`; record `GroupHit` e `GroupedSearchResult`.
- `src/main/java/com/example/demo/ApiController.java` — endpoint `GET /search/grouped`, helper `parseMode` condiviso.
- `src/test/java/com/example/demo/SearchServiceGroupingTest.java` — unit test della funzione `group`.
- `scripts/curl-grouped.sh` — curl di verifica end-to-end.
- `README.md` — documenta l'endpoint.

---

### Task 1: Config, validazione e refactor `retrieve`

**Files:**
- Modify: `src/main/resources/application.yml` (blocco `app.search`, dopo `hybrid`)
- Modify: `src/main/java/com/example/demo/SearchService.java`

**Interfaces:**
- Consumes: niente (primo task).
- Produces:
  - campi `groupWindow`, `maxGroups`, `chunksPerGroup` (int).
  - `private record Retrieval(float knnTop, List<ChunkHit> ranked)`.
  - `private Retrieval retrieve(String question, SearchMode mode, SearchFilters filters, int limit)`.
  - `private List<ChunkHit> fuse(List<SearchHit<ChunkDocument>> knnHits, List<SearchHit<ChunkDocument>> bm25Hits, int limit)`.
  - `private List<SearchHit<ChunkDocument>> bm25(String question, List<Query> filterQueries, int maxResults)`.

- [ ] **Step 1: Aggiungere la config `grouped` in `application.yml`**

Dentro `app.search`, subito dopo il blocco `hybrid` (dopo `rrf-k: 60`, quindi prima di `http-log`), inserire:

```yaml
    grouped:
      # quanti candidati da cui pescare i contentId prima di raggruppare;
      # deve essere <= num-candidates
      group-window: 50
      # quanti contentId distinti restituire al massimo
      max-groups: 10
      # quanti chunk di ogni documento finiscono nel contesto della risposta LLM
      chunks-per-group: 2
```

- [ ] **Step 2: Aggiungere campi e validazione al costruttore di `SearchService`**

Nella firma del costruttore, dopo il parametro `rrfK`, aggiungere:

```java
                         @Value("${app.search.hybrid.rrf-k:60}") int rrfK,
                         @Value("${app.search.grouped.group-window:50}") int groupWindow,
                         @Value("${app.search.grouped.max-groups:10}") int maxGroups,
                         @Value("${app.search.grouped.chunks-per-group:2}") int chunksPerGroup) {
```

Nel corpo, dopo il controllo `numCandidates < rankWindow` e prima delle assegnazioni `this.xxx = xxx`, aggiungere:

```java
        if (groupWindow < 1) {
            throw new IllegalArgumentException("app.search.grouped.group-window (" + groupWindow + ") deve essere >= 1");
        }
        // il kNN in /search/grouped chiede group-window vicini, che non possono superare num-candidates
        if (groupWindow > numCandidates) {
            throw new IllegalArgumentException("app.search.grouped.group-window (" + groupWindow
                    + ") deve essere <= app.search.num-candidates (" + numCandidates + ")");
        }
        if (maxGroups < 1) {
            throw new IllegalArgumentException("app.search.grouped.max-groups (" + maxGroups + ") deve essere >= 1");
        }
        if (chunksPerGroup < 1) {
            throw new IllegalArgumentException(
                    "app.search.grouped.chunks-per-group (" + chunksPerGroup + ") deve essere >= 1");
        }
```

E in fondo, dopo `this.rrfK = rrfK;`:

```java
        this.groupWindow = groupWindow;
        this.maxGroups = maxGroups;
        this.chunksPerGroup = chunksPerGroup;
```

Aggiungere i campi in fondo all'elenco dei `private final`:

```java
    private final int rankWindow;
    private final int rrfK;
    private final int groupWindow;
    private final int maxGroups;
    private final int chunksPerGroup;
```

- [ ] **Step 3: Rendere `bm25` e `fuse` parametrici sul limit**

Sostituire la firma e il body di `bm25` con:

```java
    // BM25 su content, con gli stessi filtri in bool.filter (non influiscono sullo score)
    private List<SearchHit<ChunkDocument>> bm25(String question, List<Query> filterQueries, int maxResults) {
        Query match = Query.of(q -> q.bool(b -> b
                .must(m -> m.match(t -> t.field("content").query(question)))
                .filter(filterQueries)));
        NativeQuery query = NativeQuery.builder()
                .withQuery(match)
                .withMaxResults(maxResults)
                .build();
        return operations.search(query, ChunkDocument.class).getSearchHits();
    }
```

In `fuse`, cambiare la firma e il `.limit(...)`:

```java
    private List<ChunkHit> fuse(List<SearchHit<ChunkDocument>> knnHits,
                                List<SearchHit<ChunkDocument>> bm25Hits, int limit) {
```

e, nello stream, sostituire `.limit(topK)` con `.limit(limit)`.

- [ ] **Step 4: Aggiungere il record `Retrieval` e il metodo `retrieve`**

Subito dopo i metodi `knn`/`bm25` (prima di `fuse`), aggiungere:

```java
    /** Hit kNN del miglior vicino (0 se vuoto) + lista ordinata (kNN in SEMANTIC, RRF in HYBRID). */
    private record Retrieval(float knnTop, List<ChunkHit> ranked) {}

    /**
     * Pipeline di retrieval condivisa: embed -> kNN filtrato (+ BM25 fuso con RRF in HYBRID).
     * In HYBRID ogni lista usa {limit} risultati (almeno rank-window), poi la fusione taglia a {limit}.
     */
    private Retrieval retrieve(String question, SearchMode mode, SearchFilters filters, int limit) {
        List<Query> filterQueries = filters.toQueries();
        float[] queryVector = embeddingModel.embed(question);
        int pool = mode == SearchMode.HYBRID ? Math.max(rankWindow, limit) : limit;
        List<SearchHit<ChunkDocument>> knnHits = knn(queryVector, filterQueries, pool);
        // la lista kNN e' ordinata per score decrescente: il primo e' sempre il kNN piu' alto
        float knnTop = knnHits.isEmpty() ? 0f : knnHits.get(0).getScore();
        List<ChunkHit> ranked = mode == SearchMode.HYBRID
                ? fuse(knnHits, bm25(question, filterQueries, pool), limit)
                : knnHits.stream().map(h -> ChunkHit.of(h.getContent(), h.getScore(), h.getScore(), null)).toList();
        return new Retrieval(knnTop, ranked);
    }
```

- [ ] **Step 5: Riscrivere `search` per usare `retrieve` (stesso comportamento)**

Sostituire l'intero corpo di `search(...)` con:

```java
    public SearchResult search(String question, SearchMode mode, SearchFilters filters) {
        Retrieval retrieval = retrieve(question, mode, filters, topK);

        // soglia di rilevanza, sempre sullo score kNN (0-1) anche in HYBRID: lo score RRF
        // dipende solo dalle posizioni, non dice se il primo chunk parla davvero della domanda.
        // Sotto soglia la risposta e' fissa e NON passa dal LLM: deterministica e senza costo.
        if (retrieval.knnTop() < minScore) {
            log.info("nessun chunk sopra la soglia: mode={} top={} minScore={} filters={} -> {}",
                    mode, retrieval.knnTop(), minScore, filters, question);
            return new SearchResult(noAnswer, mode, List.of(), 0);
        }

        List<ChunkHit> ranked = retrieval.ranked();
        String answer = answerFor(ranked, question);
        return new SearchResult(answer, mode, ranked, ranked.size());
    }
```

Nota: `answerFor` non esiste ancora in questo task; per non rompere la compilazione, in questo step estrarre da `search` il blocco contesto+LLM nel metodo:

```java
    /** Contesto "[source#index] testo" dei chunk dati -> una risposta LLM. */
    private String answerFor(List<ChunkHit> chunks, String question) {
        StringBuilder context = new StringBuilder();
        for (ChunkHit hit : chunks) {
            context.append("[").append(hit.source()).append("#")
                    .append(hit.chunkIndex()).append("] ")
                    .append(hit.content()).append("\n\n");
        }
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(u -> u.text("""
                        Contesto:
                        {context}
                        Domanda: {question}
                        """)
                        .param("context", context.toString().strip())
                        .param("question", question))
                .call()
                .content();
    }
```

- [ ] **Step 6: Compilare**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS (nessun errore di compilazione).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/application.yml src/main/java/com/example/demo/SearchService.java
git commit -m "SearchService: config grouped e refactor retrieve condiviso"
```

---

### Task 2: Funzione di raggruppamento pura (TDD)

**Files:**
- Test: `src/test/java/com/example/demo/SearchServiceGroupingTest.java`
- Modify: `src/main/java/com/example/demo/SearchService.java`

**Interfaces:**
- Consumes: `SearchService.ChunkHit` (record esistente: `source, chunkIndex, score, knnScore, bm25Score, content, langId, contentId, topics, filename`).
- Produces:
  - `public record GroupHit(String contentId, String source, String langId, List<String> topics, String filename, float score, List<ChunkHit> chunks, String answer)`.
  - `static List<GroupHit> group(List<ChunkHit> ranked, int chunksPerGroup, int maxGroups)`.

- [ ] **Step 1: Scrivere il test che fallisce**

Creare `src/test/java/com/example/demo/SearchServiceGroupingTest.java`:

```java
package com.example.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceGroupingTest {

    private static SearchService.ChunkHit hit(String contentId, String source, int chunkIndex, float score) {
        return new SearchService.ChunkHit(source, chunkIndex, score, score, null,
                "content-" + source + "-" + chunkIndex, "it", contentId, List.of("t"), source + ".txt");
    }

    @Test
    void tieneUnSoloChunkPerContentId_eIlPiuAlto() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-100", "a", 0, 0.9f),
                hit("C-100", "a", 1, 0.8f),
                hit("C-200", "b", 0, 0.7f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 10);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).contentId()).isEqualTo("C-100");
        assertThat(groups.get(0).score()).isEqualTo(0.9f);
        assertThat(groups.get(0).chunks()).hasSize(1);
    }

    @Test
    void ordinaIGruppiPerScoreDecrescente() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-100", "a", 0, 0.9f),
                hit("C-200", "b", 0, 0.85f),
                hit("C-300", "c", 0, 0.5f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 10);

        assertThat(groups).extracting(SearchService.GroupHit::contentId)
                .containsExactly("C-100", "C-200", "C-300");
    }

    @Test
    void tieneFinoAChunksPerGroupChunkDelDocumento() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-100", "a", 0, 0.9f),
                hit("C-100", "a", 1, 0.8f),
                hit("C-100", "a", 2, 0.7f),
                hit("C-200", "b", 0, 0.6f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 2, 10);

        assertThat(groups.get(0).chunks()).extracting(SearchService.ChunkHit::chunkIndex)
                .containsExactly(0, 1);
    }

    @Test
    void limitaIlNumeroDiGruppi() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-1", "a", 0, 0.9f),
                hit("C-2", "b", 0, 0.8f),
                hit("C-3", "c", 0, 0.7f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 2);

        assertThat(groups).hasSize(2);
    }

    @Test
    void gestisceContentIdNulloComeGruppo() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit(null, "a", 0, 0.9f),
                hit(null, "a", 1, 0.8f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 2, 10);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).contentId()).isNull();
        assertThat(groups.get(0).chunks()).hasSize(2);
    }
}
```

- [ ] **Step 2: Eseguire il test e verificare che fallisca**

Run: `mvn -q -Dtest=SearchServiceGroupingTest test`
Expected: FAIL in compilazione con "cannot find symbol: method group" e "class GroupHit".

- [ ] **Step 3: Implementare `GroupHit` e `group`**

In `SearchService`, accanto agli altri record (vicino a `ChunkHit`), aggiungere:

```java
    /** Un contentId: il suo miglior chunk (score = top del gruppo) + i chunk di contesto e la risposta. */
    public record GroupHit(String contentId, String source, String langId, List<String> topics,
                           String filename, float score, List<ChunkHit> chunks, String answer) {}
```

Subito dopo `accumulate(...)`, aggiungere la funzione pura:

```java
    /**
     * Raggruppa per contentId tenendo i migliori {chunksPerGroup} chunk di ogni documento.
     * Presuppone {ranked} ordinato per score decrescente: il primo chunk di ogni gruppo e' il
     * suo top, e l'ordine di prima comparsa e' l'ordine dei gruppi per score. answer resta null.
     */
    static List<GroupHit> group(List<ChunkHit> ranked, int chunksPerGroup, int maxGroups) {
        Map<String, List<ChunkHit>> byContent = new LinkedHashMap<>();
        for (ChunkHit hit : ranked) {
            List<ChunkHit> chunks = byContent.computeIfAbsent(hit.contentId(), k -> new ArrayList<>());
            if (chunks.size() < chunksPerGroup) {
                chunks.add(hit);
            }
        }
        return byContent.values().stream()
                .limit(maxGroups)
                .map(chunks -> toGroup(chunks, null))
                .toList();
    }

    private static GroupHit toGroup(List<ChunkHit> chunks, String answer) {
        ChunkHit top = chunks.get(0);
        return new GroupHit(top.contentId(), top.source(), top.langId(), top.topics(),
                top.filename(), top.score(), chunks, answer);
    }
```

- [ ] **Step 4: Eseguire il test e verificare che passi**

Run: `mvn -q -Dtest=SearchServiceGroupingTest test`
Expected: BUILD SUCCESS, 5 test verdi.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/example/demo/SearchServiceGroupingTest.java src/main/java/com/example/demo/SearchService.java
git commit -m "SearchService: raggruppamento per contentId con test unitari"
```

---

### Task 3: Metodo `searchGrouped` e record di risposta

**Files:**
- Modify: `src/main/java/com/example/demo/SearchService.java`

**Interfaces:**
- Consumes: `retrieve(...)`, `group(...)`, `answerFor(...)`, i campi `groupWindow`/`maxGroups`/`chunksPerGroup`.
- Produces:
  - `public GroupedSearchResult searchGrouped(String question, SearchMode mode, SearchFilters filters, boolean answer)`.
  - `public record GroupedSearchResult(String query, SearchMode mode, List<GroupHit> groups, int chunksUsed, boolean noAnswer)`.

- [ ] **Step 1: Aggiungere il record di risposta**

Accanto a `SearchResult`, aggiungere:

```java
    /** groups = un elemento per contentId; noAnswer = true quando nessun gruppo passa la soglia. */
    public record GroupedSearchResult(String query, SearchMode mode, List<GroupHit> groups,
                                      int chunksUsed, boolean noAnswer) {}
```

- [ ] **Step 2: Implementare `searchGrouped`**

Subito dopo il metodo `search(...)`, aggiungere:

```java
    /**
     * Come {@link #search}, ma in risposta raggruppa per contentId: per ogni documento il chunk
     * con score piu' alto, gruppi ordinati per score decrescente, tagliati a max-groups.
     * Con answer=true chiede al LLM una risposta per documento (sui suoi migliori chunks).
     * La soglia guarda sempre il miglior kNN globale, come in /search.
     */
    public GroupedSearchResult searchGrouped(String question, SearchMode mode, SearchFilters filters, boolean answer) {
        Retrieval retrieval = retrieve(question, mode, filters, groupWindow);

        if (retrieval.knnTop() < minScore) {
            log.info("nessun chunk sopra la soglia: mode={} top={} minScore={} filters={} -> {}",
                    mode, retrieval.knnTop(), minScore, filters, question);
            return new GroupedSearchResult(question, mode, List.of(), 0, true);
        }

        List<GroupHit> groups = group(retrieval.ranked(), chunksPerGroup, maxGroups);
        if (answer) {
            groups = groups.stream()
                    .map(g -> new GroupHit(g.contentId(), g.source(), g.langId(), g.topics(), g.filename(),
                            g.score(), g.chunks(), answerFor(g.chunks(), question)))
                    .toList();
        }
        int chunksUsed = groups.stream().mapToInt(g -> g.chunks().size()).sum();
        return new GroupedSearchResult(question, mode, groups, chunksUsed, false);
    }
```

- [ ] **Step 3: Compilare ed eseguire i test esistenti**

Run: `mvn -q test`
Expected: BUILD SUCCESS, i 5 test di raggruppamento verdi.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/demo/SearchService.java
git commit -m "SearchService: searchGrouped con risposta LLM opzionale per contentId"
```

---

### Task 4: Endpoint `GET /search/grouped`

**Files:**
- Modify: `src/main/java/com/example/demo/ApiController.java`

**Interfaces:**
- Consumes: `SearchService.searchGrouped(...)`, `SearchService.GroupedSearchResult`.
- Produces: `GET /search/grouped?q=&mode=&answer=&source=&langId=&contentId=&topic=&filename=`.

- [ ] **Step 1: Aggiungere l'import**

In `ApiController`, sotto `import com.example.demo.SearchService.SearchResult;`, aggiungere:

```java
import com.example.demo.SearchService.GroupedSearchResult;
```

- [ ] **Step 2: Estrarre l'helper `parseMode` e usarlo in `search`**

Sostituire il corpo del metodo `search(...)` con:

```java
    @GetMapping("/search")
    public SearchResult search(@RequestParam String q,
                               @RequestParam(defaultValue = "semantic") String mode,
                               @RequestParam(required = false) String source,
                               @RequestParam(required = false) String langId,
                               @RequestParam(required = false) String contentId,
                               @RequestParam(name = "topic", required = false) List<String> topics,
                               @RequestParam(required = false) String filename) {
        return searchService.search(q, parseMode(mode),
                new SearchFilters(source, langId, contentId, topics, filename));
    }

    private static SearchMode parseMode(String mode) {
        try {
            return SearchMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode deve essere semantic o hybrid");
        }
    }
```

Aggiornare anche il javadoc sopra `search` per citare entrambi gli endpoint; il commento diventa:

```java
    /**
     * RAG con filtri opzionali sui metadati. topic si ripete per piu' valori (OR):
     * /search?q=...&mode=hybrid&langId=it&topic=sport&topic=tennis
     */
```

- [ ] **Step 3: Aggiungere l'endpoint `searchGrouped`**

Subito dopo `search(...)` (dopo l'helper `parseMode`), aggiungere:

```java
    /**
     * Come /search ma raggruppa i risultati per contentId (il chunk migliore di ogni documento),
     * ordinati per score decrescente. answer=true chiede al LLM una risposta per documento:
     * /search/grouped?q=...&mode=hybrid&answer=true&langId=it&topic=errori
     */
    @GetMapping("/search/grouped")
    public GroupedSearchResult searchGrouped(@RequestParam String q,
                                             @RequestParam(defaultValue = "semantic") String mode,
                                             @RequestParam(defaultValue = "false") boolean answer,
                                             @RequestParam(required = false) String source,
                                             @RequestParam(required = false) String langId,
                                             @RequestParam(required = false) String contentId,
                                             @RequestParam(name = "topic", required = false) List<String> topics,
                                             @RequestParam(required = false) String filename) {
        return searchService.searchGrouped(q, parseMode(mode), answer,
                new SearchFilters(source, langId, contentId, topics, filename));
    }
```

- [ ] **Step 4: Compilare**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/ApiController.java
git commit -m "GET /search/grouped: risultati raggruppati per contentId"
```

---

### Task 5: Script curl di verifica end-to-end

**Files:**
- Create: `scripts/curl-grouped.sh`

**Interfaces:**
- Consumes: `GET /search/grouped`, `POST /ingest` (già esistenti).
- Produces: script eseguibile con i curl di verifica.

- [ ] **Step 1: Creare lo script**

Creare `scripts/curl-grouped.sh`:

```bash
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

h "grouped con risposta LLM per ogni contentId"
curl -s -G "$BASE_URL/search/grouped" --data-urlencode "q=cosa significa l'errore E4521?" -d answer=true

h "grouped con filtro che esclude tutto -> noAnswer true, groups vuota"
curl -s -G "$BASE_URL/search/grouped" --data-urlencode "q=cosa significa l'errore E4521?" -d contentId=C-999

h "grouped mode non valido -> 400"
curl -s -o /dev/null -w 'HTTP %{http_code}' -G "$BASE_URL/search/grouped" -d q=test -d mode=foo

echo
```

- [ ] **Step 2: Rendere eseguibile e verificare la sintassi**

Run: `chmod +x scripts/curl-grouped.sh && bash -n scripts/curl-grouped.sh`
Expected: nessun output (sintassi valida).

- [ ] **Step 3: Verifica manuale (app + Elasticsearch attivi)**

Run:
```bash
mvn -q -DskipTests spring-boot:run
# in un altro terminale, dopo l'avvio:
./scripts/curl-metadata.sh >/dev/null && ./scripts/curl-grouped.sh
```
Expected:
- il primo curl mostra `"groups"` con un elemento per ogni `contentId` (C-100, C-101, C-200), `"noAnswer": false`, ordinati per `score` decrescente;
- con `langId=en` resta solo `C-101`;
- con `mode=hybrid` i chunk hanno `knnScore` e `bm25Score`;
- con `answer=true` ogni gruppo ha un campo `answer` non nullo;
- con `contentId=C-999` → `"noAnswer": true`, `"groups": []`;
- `mode=foo` → `HTTP 400`.

Se l'app non è avviabile in questo ambiente (manca ES o le API key), annotare nel commit che la verifica e' rimandata e non marcare il task come completo.

- [ ] **Step 4: Commit**

```bash
git add scripts/curl-grouped.sh
git commit -m "scripts: curl di verifica per /search/grouped"
```

---

### Task 6: Documentazione README

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: endpoint e semantica dei task precedenti.
- Produces: sezione README.

- [ ] **Step 1: Aggiornare l'elenco endpoint**

Nella riga dell'albero dei file che descrive `ApiController.java`, sostituire il testo con:

```
└── ApiController.java            GET /chat, GET /search, GET /search/grouped, POST /ingest
```

- [ ] **Step 2: Aggiungere la sezione `search/grouped`**

Dopo la sezione `### `semantic` vs `hybrid``, prima di `## Il fallback `no-answer``, aggiungere:

````markdown
## Risultati raggruppati per documento (`/search/grouped`)

`/search` risponde a "quali chunk parlano di X" e passa tutto a un unico prompt. `/search/grouped`
risponde a "quali *documenti* parlano di X, e ognuno cosa dice": raggruppa i risultati per
`contentId`, tiene **il chunk con lo score più alto di ogni documento** e ordina i gruppi per
score decrescente.

```bash
curl -G localhost:8080/search/grouped --data-urlencode "q=cosa significa l'errore E4521?" \
     -d mode=hybrid -d langId=it -d answer=true
```

- `mode`: `semantic` (default) o `hybrid`; stessi filtri di `/search`.
- `answer=true` (default `false`): chiede al LLM **una risposta per ogni `contentId`**, usando i
  suoi migliori `chunks-per-group` chunk. Le chiamate sono sequenziali: N gruppi = N chiamate.
- La soglia `min-score` guarda sempre il **miglior kNN globale** (come `/search`): se nessun
  chunk è sopra soglia, `noAnswer: true` e `groups: []`. In `hybrid` l'ordine è l'RRF, quindi la
  soglia non si legge sul primo elemento ma sul massimo `knnScore`.

Risposta: `groups[]` con `contentId`, metadati del documento, `score` (del suo chunk migliore),
`chunks[]` (fino a `chunks-per-group`, nell'ordine di ranking) e `answer` (`null` se
`answer=false`); più `chunksUsed` e `noAnswer`.

```yaml
app:
  search:
    grouped:
      group-window: 50    # candidati da cui pescare i contentId (<= num-candidates)
      max-groups: 10      # max contentId in risposta
      chunks-per-group: 2 # chunk del documento usati come contesto LLM (solo con answer=true)
```
````

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "README: documenta /search/grouped"
```

---

## Self-Review

**Spec coverage:**
- Flusso retrieval con pool più largo → Task 1 (`retrieve` con limit=group-window in Task 3). ✓
- Raggruppa per contentId, miglior chunk, ordine desc, taglio max-groups → Task 2. ✓
- Soglia sul top kNN globale / no-answer → Task 3. ✓
- Risposta LLM per documento con chunks-per-group → Task 3 (`answerFor` + `searchGrouped`). ✓
- Config `grouped.*` + validazioni → Task 1. ✓
- Endpoint + `400` su mode invalido → Task 4. ✓
- Refactor `retrieve` senza cambiare `/search` → Task 1 (poi `search` riscritto con stesso comportamento). ✓
- Testing manuale curl + script → Task 5. ✓
- Documentazione → Task 6. ✓
- Fuori scope (UI, LLM parallelo, errori parziali) → non implementati. ✓

**Placeholder scan:** nessun TODO/TBD; ogni step di codice mostra il codice completo.

**Type consistency:** `Retrieval(knnTop, ranked)`, `group(List<ChunkHit>, int, int)`, `GroupHit(...)`, `GroupedSearchResult(query, mode, groups, chunksUsed, noAnswer)`, `answerFor(List<ChunkHit>, String)`, `bm25(..., int maxResults)`, `fuse(..., int limit)` — nomi usati in modo coerente tra i task. `ChunkHit` invariato.