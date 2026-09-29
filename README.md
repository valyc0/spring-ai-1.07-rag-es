# demo-ai-es

Spring Boot 3.5.6 + Spring AI 1.0.7: chat e embedding su client OpenAI con `base-url` separati,
chunking + embedding e indicizzazione su Elasticsearch.

```
pom.xml
src/main/java/com/example/demo
├── DemoApplication.java
├── ChunkDocument.java            entity con setEmbedding, @Field(dense_vector)
├── ElasticIndexInitializer.java  crea l'indice col mapping se non esiste
├── IngestService.java            split -> embed(List<String>) -> save
├── HttpLoggingConfig.java        logga la request HTTP di chat/embedding
└── ApiController.java            GET /chat, POST /ingest
src/main/resources/application.yml
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

Stessa identica chiamata all'endpoint `POST /ingest`, che è il codice in
`ApiController.ingest(@RequestParam String source, @RequestBody String text)`: `source` finisce in
query string, il testo in body `text/plain` grezzo. Risposta: `{"source":"doc1","chunksIndexed":3}`.

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

### Script pronto all'uso

```bash
./scripts/curl-examples.sh              # tutto: health, ingest, indice, kNN, chat
./scripts/curl-examples.sh ingest       # solo ingest
./scripts/curl-examples.sh index        # mapping dense_vector + conteggio per source
./scripts/curl-examples.sh knn          # embed della query con curl a Jina + ricerca kNN
./scripts/curl-examples.sh chat         # richiede OPENAI_CHAT_API_KEY
```

Sub-comandi: `health | ingest | chat | index | knn | all`. Override da shell: `BASE_URL`,
`ELASTIC_URL`, `INDEX`, `SOURCE`, `SAMPLE_FILE`, `INLINE_TEXT`, `QUERY`. Lo script crea
`mio_testo.txt` se non esiste, cerca le chiavi in `.env` e fallisce con un messaggio chiaro se
manca quella necessaria al sotto-comando.

## Configurazione

| modello | base-url | api-key | path |
|---|---|---|---|
| chat (`gpt-4o-mini`) | `https://api.openai.com` | `OPENAI_CHAT_API_KEY` | `/v1/chat/completions` |
| embedding (`jina-embeddings-v5-omni-small`) | `https://api.jina.ai` | `JINA_API_KEY` | `/v1/embeddings` |

Tutte le chiavi/URL sono sovrascrivibili da env (`OPENAI_CHAT_BASE_URL`, `OPENAI_CHAT_MODEL`,
`JINA_BASE_URL`, `JINA_EMBEDDING_MODEL`, `ELASTIC_URIS`). Se il `base-url` contiene già `/v1`,
impostare di conseguenza `completions-path` / `embeddings-path`.

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
task sbagliato peggiora i risultati).

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
partono.

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
