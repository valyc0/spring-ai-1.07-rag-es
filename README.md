# demo-ai-es

Spring Boot 3.5.6 + Spring AI 1.0.7: RAG su Elasticsearch — ingest con chunking + embedding,
`/search` che recupera i chunk per similarità e risponde usando solo quelli. Chat ed embedding
passano entrambi da client OpenAI con `base-url` separati (Groq per la chat, Jina per gli
embedding).

```
pom.xml
src/main/java/com/example/demo
├── DemoApplication.java
├── ChunkDocument.java            entity: content, embedding (dense_vector) + metadati keyword
├── ElasticIndexInitializer.java  crea l'indice col mapping, o aggiunge i campi nuovi se esiste
├── IngestRequest.java            body JSON di POST /ingest (testo + metadati)
├── IngestService.java            split -> embed(List<String>) a blocchi -> save -> refresh
├── SearchFilters.java            filtri sui metadati -> term/terms query
├── SearchMode.java               SEMANTIC (kNN) | HYBRID (kNN + BM25, RRF)
├── SearchService.java            embed(query) -> kNN [+ BM25] filtrati -> soglia -> contesto -> LLM
├── HttpLoggingConfig.java        logga la request HTTP di chat/embedding
├── AgentSearchService.java       ricerca agentica: il LLM chiama il tool searchKnowledgeBase
└── ApiController.java            GET /chat, GET /search, GET /agent/search, POST /ingest
src/main/resources/application.yml
scripts/curl-examples.sh          demo completa: ingest, search, index, knn, chat
scripts/search.sh                 singola chiamata /search con request, response e timing
```

## Avvio

Le chiavi si leggono dal file `.env` in locale (nella root del progetto), che è gitignored:

```bash
cp .env.example .env     # poi riempi JINA_API_KEY
mvn spring-boot:run

curl -X POST "localhost:8080/ingest?source=doc1" \
     -H "Content-Type: text/plain" \
     --data-binary "@mio_testo.txt"

curl "localhost:8080/chat?q=ciao"
```

`.env` è un normale file `KEY=VALUE` caricato da Spring Boot con:

```yaml
spring:
  config:
    import: optional:file:.env[.properties]
```

(`optional` = se il file non c'è l'app parte ugualmente e le chiavi si prendono dall'env della
shell, quindi funziona anche in CI o in Docker dove sono già iniettate come variabili.)

```bash
.gitignore      # .env escluso, .env.example versionato
.env            # SOLO locale (chmod 600)
.env.example    # template da copiare
```

## Ingest: curl vs client Java

Stessa identica chiamata all'endpoint `POST /ingest` nella variante **senza metadati**, che è il
codice in `ApiController.ingestText(@RequestParam String source, @RequestBody String text)`:
`source` finisce in query string, il testo in body `text/plain` grezzo. Risposta:
`{"source":"doc1","chunksIndexed":3}`. Per l'ingest con metadati (body JSON) vedi
"Metadati e filtri" più sotto.

**curl** (file o testo inline)

```bash
curl -X POST "localhost:8080/ingest?source=doc1" \
     -H "Content-Type: text/plain" \
     --data-binary "@mio_testo.txt"

curl -X POST "localhost:8080/ingest?source=doc1" \
     -H "Content-Type: text/plain" \
     --data-binary "Un bel tramonto sulla spiaggia."
```

**Java — `RestClient` di Spring 6** (incluso in `spring-boot-starter-web`)

```java
RestClient client = RestClient.create();

String risposta = client.post()
        .uri("http://localhost:8080/ingest?source={source}", "doc1")
        .contentType(MediaType.TEXT_PLAIN)
        .body("Un bel tramonto sulla spiaggia.")   // .body(Files.readString(Path.of("mio_testo.txt")))
        .retrieve()
        .body(String.class);
// -> {"source":"doc1","chunksIndexed":1}
```

**Java — vecchia API `RestTemplate`**

```java
RestTemplate rest = new RestTemplate();
String risposta = rest.postForObject(
        "http://localhost:8080/ingest?source={source}",
        new HttpEntity<>("Un bel tramonto sulla spiaggia.", headers( MediaType.TEXT_PLAIN)),
        String.class,
        Map.of("source", "doc1"));
```

**Java — da un test (MockMvc / `@SpringBootTest(webEnvironment = RANDOM_PORT)`)**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestApiTest {

    @Autowired TestRestTemplate rest;   // baseUrl gia' puntata alla porta random

    @Test
    void ingesta() {
        IngestResponse r = rest.postForObject("/ingest?source=doc1", "Un bel tramonto sulla spiaggia.",
                IngestResponse.class);
        assertThat(r.chunksIndexed()).isGreaterThan(0);
    }

    record IngestResponse(String source, int chunksIndexed) {}
}
```

**Cosa succede dietro** — `IngestService.ingest()`: split in chunk da 800 char con overlap 100 →
una sola `embeddingModel.embed(chunks)` (tutti i chunk in una request) →
`operations.save(docs)` su Elasticsearch. La request HTTP verso Jina che ne esce è quella loggata
in nota 9.

## `/agent/search`: ricerca agentica

`GET /agent/search?q=...` dà al LLM un tool Spring AI (`@Tool searchKnowledgeBase`, che riusa
`SearchService.retrieve()`: stessi filtri, soglia e modalità semantic/hybrid di `/search`). Il LLM decide
quante ricerche fare e con quale query: riformula se non trova, scompone domande multiple. Il loop
tool-call lo esegue Spring AI dentro `call()`. La risposta ha `answer`, `steps` (le chiamate al tool
con query/mode/filtri/hits) e `sources`. Senza nessun chunk trovato risponde `no-answer`, senza LLM.
`langId` e `contentId` opzionali vincolano tutte le ricerche dell'agente a quel contenuto: sono
applicati dal codice a ogni chiamata del tool, il LLM non può ignorarli. Per domande su *di cosa parla un documento* (che la ricerca per similarità scarterebbe sotto soglia) ci sono
`listDocuments` (aggregazione su `source`) e `getDocumentChunks` (primi N chunk in ordine, senza kNN né soglia).
Il limite di 4 ricerche è solo nel prompt (Spring AI 1.0 non ha un tetto alle iterazioni).

```bash
curl -G localhost:8080/agent/search --data-urlencode "q=Di che colore sono le palline da tennis?"
```

## `/search`: il RAG completo

`GET /search?q=...` chiude il ciclo che `/ingest` aveva solo aperto. Tutto dentro l'app:

```bash
curl -X POST "localhost:8080/ingest?source=palline" \
     -H "Content-Type: text/plain" \
     --data-binary "Le palline da tennis nel cesto sono verdi e lucide."

curl -G "localhost:8080/search" --data-urlencode "q=Di che colore sono le palline da tennis?"
```

```json
{
  "answer": "Le palline da tennis sono **verdi** (e lucide)【palline#0】.",
  "sources": [
    {"source": "palline", "chunkIndex": 0, "score": 0.8779, "content": "Le palline da tennis nel cesto sono verdi e lucide."},
    {"source": "gatto",   "chunkIndex": 0, "score": 0.6432, "content": "Il gatto dorme tutto il giorno sul tappeto verde in salotto."}
  ],
  "chunksUsed": 5
}
```

`answer` è la risposta del LLM, `sources` i top-k chunk recuperati con il loro score cosine.
Il gatto è secondo per una ragione sola: contiene la parola *verde*, ed è un falso positivo
che non guasta la risposta ma mostra che il kNN è puramente semantico, non a parole chiave.

### I 4 passi (`SearchService.search()`)

| # | passo | dove |
|---|---|---|
| 1 | embed della domanda con lo stesso modello dell'ingest | `SearchService.java:87` |
| 2 | kNN filtrato su `chunks` (+ BM25 e RRF in modalità `hybrid`) | `SearchService.java:91-106` |
| 3 | soglia di rilevanza sullo score kNN del chunk più vicino | `SearchService.java:98` |
| 4 | contesto `[source#chunkIndex] testo` → `ChatClient` → risposta | `SearchService.java:109-128` |

Al passo 2 la richiesta contiene **solo** `knn`, senza `query`: se ci fosse anche una query
(es. `match_all`) ES sommerebbe i due score e il valore confrontato con `min-score` non sarebbe
più lo score kNN (vedi "I tre limiti da conoscere"). Il refresh dell'indice lo fa l'ingest dopo
il `save()` (nota 6), quindi una `/search` subito dopo un ingest trova già i chunk.

**Non serve `spring-ai-starter-vector-store`.** `NativeQuery.withKnnSearches(...)` in
spring-data-elasticsearch 5.5.4 finisce già in `SearchRequest.knn()`: il kNN è supportato
nativamente da `ElasticsearchOperations`. Con `VectorStore` avresti la stessa ricerca con più
strati sopra e un mapping diverso per l'indice.

## Metadati e filtri

Ogni chunk porta, come campi di **primo livello** in `_source` (non sotto un oggetto
`metadata`), i metadati del documento da cui viene:

| campo | tipo ES | filtro |
|---|---|---|
| `source` | keyword | `source=...` |
| `langId` | keyword | `langId=it` |
| `contentId` | keyword | `contentId=C-100` |
| `topics` | keyword (array) | `topic=a&topic=b` (OR: basta uno) |
| `filename` | keyword | `filename=manuale.pdf` |

Sono `keyword` perché servono come filtri esatti, non per la ricerca full-text: `langId=it` non
trova `IT`. Tra campi diversi vale AND.

**Ingest con metadati** — body JSON, solo `source` e `text` obbligatori; i metadati vengono
copiati su ogni chunk del documento:

```bash
curl -X POST localhost:8080/ingest -H "Content-Type: application/json" -d '{
  "source": "manuale-it",
  "text": "Il codice errore E4521 indica che il filtro della lavatrice è intasato...",
  "langId": "it",
  "contentId": "C-100",
  "topics": ["lavatrice", "errori"],
  "filename": "manuale_lavatrice_it.pdf"
}'
```

**Ricerca filtrata** — `mode` è `semantic` (default) o `hybrid`, i filtri sono tutti opzionali:

```bash
curl -G localhost:8080/search --data-urlencode "q=cosa significa l'errore E4521?" \
     -d mode=hybrid -d langId=it -d topic=errori -d topic=cucina
```

Ogni elemento di `sources` riporta i metadati del chunk, `score` (quello usato per
l'ordinamento), `knnScore` e `bm25Score` (`null` se il chunk non era in quella lista).

**I filtri stanno dentro le query, non in `post_filter`.** Nel kNN vanno in `knn.filter`: ES
cerca i vicini *solo* tra i chunk che passano i filtri, quindi restituisce comunque `k` risultati.
Con un post-filter ES troverebbe prima i `k` vicini globali e poi scarterebbe quelli fuori
filtro, e con filtri selettivi resterebbero zero risultati. Nel BM25 vanno in `bool.filter`, che
non influisce sullo score.

**Aggiungere campi a un indice esistente.** All'avvio `ElasticIndexInitializer` chiama
`putMapping()` se l'indice c'è già: ES accetta l'aggiunta di campi nuovi, quindi i metadati si
aggiungono senza perdere i dati. I chunk indicizzati prima non hanno i metadati e **non passano
nessun filtro** su quei campi. Cambiare il *tipo* di un campo esistente invece fallisce: lì
serve `scripts/clear-index.sh --drop` + riavvio + reingest.

### `semantic` vs `hybrid`

- **`semantic`**: solo kNN, `k = top-k`. Trova i chunk che *parlano della stessa cosa*, anche in
  altre lingue, ma può mancare corrispondenze esatte (codici, sigle, nomi propri).
- **`hybrid`**: kNN e BM25 su `content`, ciascuno con `rank-window` risultati e gli stessi filtri,
  fusi con **Reciprocal Rank Fusion**: `score = Σ 1 / (rrf-k + rank)`. RRF usa solo le posizioni,
  quindi non serve rendere confrontabili score su scale diverse (kNN 0–1, BM25 illimitato). Un
  chunk primo in entrambe le liste vince; uno presente in una sola lista prende solo quel termine.

**RRF è calcolato nell'app, non da ES.** Sulla licenza *basic* sia il retriever `rrf` sia
`linear` rispondono `403 current license is non-compliant` (verificato su ES 9.2.1). Due
richieste + fusione in Java funzionano con qualunque licenza.

**La soglia `min-score` guarda sempre lo score kNN**, anche in `hybrid`: lo score RRF dipende
solo dalle posizioni (il primo prende sempre ~0.016–0.033) e non dice se il chunk parla davvero
della domanda. Quindi in `hybrid` una domanda con un match *solo* lessicale (BM25 alto, kNN sotto
soglia) riceve comunque `no-answer`: è una scelta prudente, perché BM25 con l'analyzer standard
fa match anche su parole comuni ("di", "che") e da solo non è un buon segnale di rilevanza.

```yaml
app:
  search:
    hybrid:
      rank-window: 20   # risultati per lista prima della fusione (>= top-k, <= num-candidates)
      rrf-k: 60         # costante di RRF, stesso default di ES
```

## Il fallback `no-answer`

Il problema che risolve: la kNN **restituisce sempre `top-k` chunk**, anche se nessuno parla
della domanda. Con l'indice pieno solo di "palline verdi", la ricerca su *"Dove si trova la Torre
Eiffel?"* restituisce comunque 5 chunk — solo che i "più vicini" segnano ~0.69 quando i giusti
segnerebbero ~0.89. Sono rumore. Mandarli al LLM è la via classica all'hallucination: il modello
vede frasi plausibili più una domanda senza risposta, e risponde inventando.

La guardia taglia a monte, prima che il rumore raggiunga il modello:

```java
if (matchers.isEmpty() || matchers.get(0).getScore() < minScore) {
    log.info("nessun chunk sopra la soglia: top={} minScore={} -> {}", top, minScore, question);
    return new SearchResult(noAnswer, List.of(), 0);
}
```

Configurabile in `application.yml`:

```yaml
app:
  search:
    top-k: 5
    num-candidates: 50   # >= top-k: quanti vettori ES valuta prima di ridurli
    min-score: 0.76
    no-answer: "Non ho informazioni a riguardo."
```

### Due livelli di difesa, su piani diversi

La soglia è la difesa **deterministica**: sotto, non si chiede niente al LLM.
Il system prompt è la difesa **probabilistica**:

```
Rispondi alla domanda usando SOLO il contesto fornito.
Non usare conoscenze esterne al contesto e non inventare.
```

La soglia intercetta il caso *chiaro* (nessun chunk parla della domanda). Il prompt intercetta
il caso *ambiguo*: chunk sopra soglia ma senza la risposta specifica. Entrambi verificati — con
"campionato 1994" e 5 chunk sopra soglia l'LLM ha risposto *"Il contesto fornito non contiene
informazioni su chi abbia vinto"*. Ma è una cortesia del modello, non una garanzia: riscritto il
prompt può cambiare comportamento. La soglia no.

### Da dove viene 0.76

Misurato, non indovinato. 11 sonde sullo stesso indice di 5 documenti:

| | score top-1 |
|---|---|
| 5 domande con risposta | 0.7955 – 0.9169 |
| 6 domande senza risposta | 0.6897 – 0.7321 |

Il gap è tra 0.7321 e 0.7955, quindi 0.76 separa le due popolazioni. Il log mostra ogni
rifiuto, utile per rimisurare:

(Le misure originali erano 1.69–1.92 perché la query conteneva anche un `match_all`, che vale
1.0 e ES lo sommava allo score kNN; tolto il `match_all`, gli stessi valori calano di 1.)

```
SearchService : nessun chunk sopra la soglia: top=0.6896982 minScore=0.76 -> Dove si trova la Torre Eiffel?
```

La soglia è tarata su 4 chunk, quindi il margine è netto ma fragile. Su corpus grandi i "cffi"
del rumore salgono e il massimo "senza risposta" può superare il minimo "con risposta": va
rimisurata man mano che l'indice cresce.

### I tre limiti da conoscere

**Il valore dipende dal modello di embedding.** Con `similarity: cosine` ES restituisce
`(1 + cos) / 2`, quindi lo score sta su 0–1 e la normalizzazione dei vettori non lo cambia (il
coseno divide già per le norme). Ma `0.76` è una misura sulla distribuzione di questo modello:
cambiando modello **tutto il numero va rifatto**.

**La richiesta deve restare solo kNN.** Se alla `NativeQuery` si aggiunge una `query`, ES somma
il suo score a quello kNN: con un `match_all` (score 1.0) i valori salgono a 1.7–1.9, **tutte** le domande superano
`0.76` e il fallback `no-answer` non scatta più.

**La soglia guarda solo il primo risultato.** Se `matchers.get(0)` è a 0.90 ma il secondo è a
0.30 e contiene la risposta vera, il chunk da 0.30 finisce nel contesto comunque (o peggio,
viene scartato insieme a `sources`). Con `top-k: 5` non l'ho visto accadere; su corpus più grandi
va gestito filtrando per chunk invece che per posizione.

**Una soglia non fa una garanzia.** Sotto il limite ci sono risposte che troveresti (domanda
formulata in modo strano, documento che parla della stessa cosa con parole diverse), sopra ci
sono falsi positivi (domanda generica che matcha con qualsiasi cosa). Ma il danno è una risposta
mancata, non una risposta falsa: sotto la soglia non si chiede, sopra la soglia si dà contesto
vero.

### Costo

La guardia evita la completion: l'embedding c'è sempre (serve a cercare), la chiamata LLM no.
Verificato contando le richieste a Groq nel log — due domande sotto soglia, contatore fermo:

```
grep -c "api.groq.com" app.log   # 6 -> 6
```

Su un corpus dove la maggior parte delle domande non trova niente, si azzera il costo dell'LLM.

### Prova che il RAG legge davvero l'indice

La domanda più forte è su un contenuto arbitrario, che il modello non può indovinare:

```bash
# indice con 3 documenti che NON contengono "palline"
curl -G "localhost:8080/search" --data-urlencode "q=Di che colore sono le palline da tennis?"
# -> {"answer":"Non ho informazioni a riguardo.","sources":[],"chunksUsed":0}

curl -X POST "localhost:8080/ingest?source=palline" -H "Content-Type: text/plain" \
     --data-binary "Le palline da tennis nel cesto sono verdi e lucide."

# stessa domanda, stesso identico testo
curl -G "localhost:8080/search" --data-urlencode "q=Di che colore sono le palline da tennis?"
# -> {"answer":"Le palline da tennis sono verdi (e lucide)【palline#0】.", "sources":[{"source":"palline",...}]}
```

Stessa domanda, risposta opposta, unica variabile cambiata: l'indice. `scripts/curl-examples.sh
search` fa esattamente questa sequenza con `BALLS_TEXT`/`BALLS_QUERY`.

### Script pronto all'uso

```bash
./scripts/curl-examples.sh              # tutto: health, ingest, indice, search, kNN, chat
./scripts/curl-examples.sh ingest       # solo ingest
./scripts/curl-examples.sh search       # solo il RAG: embed -> kNN -> risposta
./scripts/curl-examples.sh index        # mapping dense_vector + conteggio per source
./scripts/curl-examples.sh knn          # embed della query con curl a Jina + ricerca kNN
./scripts/curl-examples.sh chat         # richiede OPENAI_CHAT_API_KEY

./scripts/search.sh                     # /search con request, response pretty e timing
./scripts/search.sh "chi ha creato Python?"   # domanda custom
```

Sub-comandi: `health | ingest | search | chat | index | knn | all`. Override da shell: `BASE_URL`,
`ELASTIC_URL`, `INDEX`, `SOURCE`, `SAMPLE_FILE`, `INLINE_TEXT`, `QUERY`, `TRAIN_TEXT`,
`RAG_QUERY`, `BALLS_TEXT`, `BALLS_QUERY`. Lo script crea `mio_testo.txt` se non esiste, cerca le
chiavi in `.env` (root del progetto, poi cwd) e fallisce con un messaggio chiaro se manca quella
necessaria al sotto-comando.

## Configurazione

| modello | base-url | api-key | path |
|---|---|---|---|
| chat (`openai/gpt-oss-120b`) | `https://api.groq.com/openai` | `OPENAI_CHAT_API_KEY` | `/v1/chat/completions` (default) |
| embedding (`jina-embeddings-v5-omni-small`) | `https://api.jina.ai` | `JINA_API_KEY` | `/v1/embeddings` |

Tutte le chiavi/URL sono sovrascrivibili da env (`OPENAI_CHAT_BASE_URL`, `OPENAI_CHAT_MODEL`,
`JINA_BASE_URL`, `JINA_EMBEDDING_MODEL`, `ELASTIC_URIS`). Il `base-url` va **senza** `/v1`: il
`*-path` di default (`/v1/chat/completions`, `/v1/embeddings`) viene appendito. Per l'embedding
Jina il default è giusto, perché `https://api.jina.ai` non contiene già `/v1`.

La chat di default non va su OpenAI ma su **Groq**: `openai/gpt-oss-120b` con
`https://api.groq.com/openai`. Groq espone un'API OpenAI-compatible (stesso path
`/v1/chat/completions`, stesso body `{"model","messages"}`), quindi serve lo stesso
`spring-ai-starter-model-openai` — cambiano solo `base-url` e `model`. La chiave Groq va in
`OPENAI_CHAT_API_KEY` (https://console.groq.com/keys). Per tornare a OpenAI:
`OPENAI_CHAT_BASE_URL=https://api.openai.com` + `OPENAI_CHAT_MODEL=gpt-4o-mini`.

## Note emerse in fase di setup

**1. Lo starter OpenAI è un blocco unico.**
In Spring AI 1.0.7 esiste solo `spring-ai-starter-model-openai`: non ci sono starter separati
chat/embedding. Quello starter attiva anche audio, image e moderation, e ognuno pretende la propria
api-key: senza escluderli l'app non parte (`OpenAI API key must be set`). In `application.yml` ci
sono le quattro `spring.autoconfigure.exclude`.

**2. Chat e embedding non sono obbligatori insieme.**
`POST /ingest` funziona anche senza `OPENAI_CHAT_API_KEY`. Due dettagli che non sono ovvi:

- la chat usa `api-key: ${OPENAI_CHAT_API_KEY:not-configured}`. Con il fallback l'app parte
  sempre; senza, un placeholder irrisolto diventerebbe la stringa letterale
  `${OPENAI_CHAT_API_KEY}` (Spring Boot ignora i placeholder non risolti nel binder) e passerebbe
  comunque l'assertion, ma è fragile da leggere. Attenzione però alla variante opposta: se
  `.env` contiene `OPENAI_CHAT_API_KEY=` **vuota**, il fallback non scatta (la variabile esiste) e
  l'assertion "api key must be set" blocca l'avvio. Per questo in `.env` / `.env.example` la
  riga è commentata.
- senza chiave solo `GET /chat` fallisce, con 401 da OpenAI.

**3. Jina si usa col client OpenAI, ma senza `task` e `normalized`.**
L'API Jina è OpenAI-compatible (stesso schema input/output di `text-embedding-3-large`), quindi
bastano `base-url`, `api-key` e `model`. Però `OpenAiEmbeddingOptions` non espone i parametri
Jina-specifici `task` (`retrieval.query` / `retrieval.passage`) né `normalized`. I vettori non
sono quindi L2-normalizzati e non applicano l'adapter LoRA per task: va bene per l'indicizzazione,
ma se aggiungi un `/search` con query reali conviene un `EmbeddingModel` custom che usi
`retrieval.passage` in ingest e `retrieval.query` in ricerca (la retrieval è asimmetrica, usare il
task sbagliato peggiora i risultati). Nota che `/search` **è** già implemented con query reali ma
senza task: funziona, però su corpus grandi la qualità del retrieval peggiora. Manca anche
`normalized`, che però non cambia lo score kNN: con `similarity: cosine` ES divide già per le
norme.

L'API grezza accetta però tutto quello che il client non espone: per vederlo basta chiamarla
direttamente con curl, con `task` e `normalized` espliciti:

```bash
curl "https://api.jina.ai/v1/embeddings" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JINA_API_KEY" \
  -d @- <<EOF
{
  "model": "jina-embeddings-v5-omni-small",
  "task": "retrieval.query",
  "normalized": true,
  "input": [
    {"text": "A beautiful sunset over the beach"},
    {"text": "Un beau coucher de soleil sur la plage"},
    {"text": "Testo aggiuntivo per la ricerca semantica"},
    {"text": "Un altro documento da trasformare in embedding"}
  ]
}
EOF
```

Risposta: `data[]` con un embedding da 1024 float per ciascun testo, in ordine di `input`
(`index` li numera). Nota che l'`input` qui è un array di oggetti `{"text": ...}`, mentre quello
che manda Spring AI (nota 9) è un array di stringhe: entrambi validi, sono forme diverse della
stessa API.

**4. `dims` è una costante di compilazione.**
Jina v5-omni-small produce 1024 dim (nano = 768; Matryoshka: 32/64/128/256/512/768/1024). Il
`dims` di `@Field(dense_vector)` non accetta SpEL, quindi è la costante
`ChunkDocument.EMBEDDING_DIMS = 1024` e deve restare allineata a
`spring.ai.openai.embedding.options.dimensions`. Cambiando modello: cambiare entrambi e
ricreare l'indice. (NB: `dimensions` finisce nel body della richiesta; l'output nativo resta
1024 che è anche la dimensione piena Matryoshka.)

**5. I vettori NON sono in `_source`, ma sono indicizzati.**
Su Elasticsearch 9.2 vale `index.mapping.exclude_source_vectors = true` (default, non impostato
esplicitamente sull'indice): il float array viene salvato nell'indice vettoriale, non copiato in
`_source`. Quindi `GET /chunks/_search` NON mostra `embedding` anche se il doc è stato indicizzato
correttamente. Per accertarsene:

```bash
curl -X POST "localhost:9200/chunks/_search" -H "Content-Type: application/json" -d '{
  "knn": {"field": "embedding", "query_vector": [...1024 float...], "k": 3, "num_candidates": 10}
}'
```

Per vederlo in `_source`: `PUT chunks/_settings {"index.mapping.exclude_source_vectors": false}`
+ reindex. Di solito non serve (si ri-embedda on demand).

**6. Un ingest "di successo" non basta come verifica.**
`operations.save()` non fa refresh, quindi il doc non è ricercabile subito: conviene
`POST /chunks/_refresh` prima di verificare. Un vettore con numero di dim sbagliato viene
comunque rifiutato da ES con 400 (`different number of dimensions`), quindi gli indici non
partono. Per questo `IngestService.ingest()` chiama `indexOps(ChunkDocument.class).refresh()`
subito dopo il `save()`: senza, un ingest e una `/search` di seguito perderebbero i chunk appena
scritti e la demo sembrerebbe rotta.

**7. Elasticsearch: container già running sulla macchina.**

```
docker.elastic.co/elasticsearch/elasticsearch:9.2.1   nome=elasticsearch
porta 9200 pubblicata, discovery.type=single-node, xpack.security.enabled=false
rete docker: google-like-search_default (172.24.0.2)
```

Da host: `http://localhost:9200`. Da un container nella stessa rete: `http://elasticsearch:9200`.
Essendo la security disattivata, `username`/`password` restano commentati: se la riattivi servono
`ELASTIC_USERNAME`/`ELASTIC_PASSWORD` e `spring.elasticsearch.ssl.*`. Nota che il client
`elasticsearch-java` è 8.18.6 (lo porta spring-data-elasticsearch 5.5.4) e funziona lo stesso
contro il server 9.2.1.

**8. Non committare le chiavi.**
Le chiavi stanno solo in `.env` (gitignored, chmod 600); nel repo finisce solo `.env.example` con
placeholder vuoti. Se `application.yml` contiene una chiave in chiaro va ruotata: anche se il
repo è locale, finisce in `target/classes/application.yml` dentro il jar.

**9. Log della request HTTP di embedding.**
`HttpLoggingConfig` registra un `ClientHttpRequestInterceptor` su `RestClient.Builder`: tutto ciò
che Spring AI manda a Jina/OpenAI viene loggato su logger `http.request` (livello INFO, spegnibile
con `app.http-log.enabled=false` o `HTTP_LOG_ENABLED=false`). L'header `Authorization` è mascherato
(`***(1 value, 72 chars)`), il body no. Copre anche `/chat`, non solo `/ingest`: i due client
condividono lo stesso `RestClient.Builder` (è un bean prototype di Spring Boot su cui interviene
il `RestClientCustomizer`).

Payload effettivo inviato a Jina per un chunk:

```
--> POST https://api.jina.ai/v1/embeddings
--> Content-Type: application/json
--> Authorization: ***(1 value, 72 chars)
--> Content-Length: 103
--> body (103 bytes): {"input":["Un bel tramonto sulla spiaggia."],"model":"jina-embeddings-v5-omni-small","dimensions":1024}
<-- 200 OK https://api.jina.ai/v1/embeddings
```

Da qui si vede che `input` è un array di stringhe (non di oggetti `{"text": ...}`), che non ci sono
`task` né `normalized`, e che i campi null (`user`, `encodingFormat`) vengono omessi dal
serializzatore. Su ingest grossi il body loggato è grosso: in produzione meglio
`app.http-log.enabled=false`, sia per rumore sia perché contiene i testi inviati al provider.

## Verifica rapida dell'indice

```bash
curl "localhost:9200/chunks/_mapping?pretty" | grep -A3 dense_vector
# atteso: "dims": 1024, "similarity": "cosine", index true
curl "localhost:9200/_cat/indices/chunks?v"
```
