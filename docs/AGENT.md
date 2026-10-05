# Ricerca agentica (`GET /agent/search`)

Questo documento spiega cosa fa l'endpoint agentico, perché esiste accanto a `/search`, come funziona
il ciclo dei tool in Spring AI e cosa fa ogni pezzo di codice. I riferimenti `File.java:riga` sono
quelli del codice al momento della scrittura.

## 1. Cos'è e perché

`/search` è una **pipeline fissa**: embed della domanda → kNN → soglia → LLM. Il codice decide tutto
(una sola ricerca, la domanda così com'è, la modalità scelta dal chiamante). Se la ricerca va male,
la risposta è `no-answer` e finisce lì.

`/agent/search` inverte il controllo: **il LLM riceve degli strumenti (tool) e decide lui** quali usare,
quante volte e con quali argomenti. Può:

- riformulare una query che non ha trovato nulla (sinonimi, termini più specifici);
- scomporre una domanda in più parti e cercare una volta per parte;
- scegliere `hybrid` (parole esatte) per codici e sigle, `semantic` per il linguaggio naturale;
- elencare e leggere un documento intero quando la domanda è "di cosa parla il documento X?".

Il codice resta responsabile di ciò che **non** va lasciato al modello: i vincoli del chiamante
(`langId`, `contentId`), la soglia di rilevanza, il tetto di chiamate, la forma della risposta.

```
GET /agent/search?q=...[&langId=it][&contentId=C-100]
```

| Parametro   | Obbligatorio | Significato |
|-------------|--------------|-------------|
| `q`         | sì           | domanda dell'utente |
| `langId`    | no           | limita **tutte** le ricerche dell'agente a quella lingua |
| `contentId` | no           | limita **tutte** le ricerche dell'agente a quel contenuto |

Risposta:

```json
{
  "answer": "Le palline da tennis sono verdi [palline#0].",
  "steps":   [ { "tool": "searchKnowledgeBase", "query": "colore palline da tennis",
                 "mode": "SEMANTIC", "filters": { "langId": null, "contentId": "C-palline", ... },
                 "hits": 1 } ],
  "sources": [ { "source": "palline", "chunkIndex": 0, "content": "...", ... } ],
  "truncated": false
}
```

- `steps`: una voce per ogni chiamata del LLM a un tool (cosa ha chiesto, con quali filtri, quanti risultati).
  È lo strumento principale per capire **come ha ragionato** l'agente. `hits = -1` = chiamata bloccata dalla guardia.
- `sources`: i chunk effettivamente passati al LLM, senza duplicati.
- `truncated`: `true` se la guardia anti-loop ha bloccato almeno una chiamata.

### Variante in streaming: `GET /agent/search/stream`

Stessi parametri, risposta `text/event-stream` (SSE). Il client vede l'agente lavorare in tempo reale:

| Evento    | Dato | Quando |
|-----------|------|--------|
| `step`    | JSON `ToolStep` (come in `steps`) | subito, a ogni chiamata a un tool |
| `token`   | pezzo di testo | durante la risposta finale |
| `replace` | testo `no-answer` | solo se nessun chunk è stato recuperato: **il client deve sostituire** il testo già mostrato |
| `done`    | JSON `{sources, truncated}` | fine normale |
| `error`   | messaggio | errore (es. 429 del provider); dopo l'`error` lo stream si chiude |

Come funziona (`AgentSearchService.stream()`): `chatClient...stream().content()` produce un `Flux<String>` di token; Spring AI
esegue comunque il ciclo dei tool dentro lo stream. Gli `step` non vengono dal `Flux` dei token ma dal tool stesso: `KnowledgeTools`
ha un listener (`onStep`) chiamato da `record(...)` a ogni chiamata, che scrive in un `Sinks.Many` da cui il controller legge.
Token e step finiscono nello stesso sink, quindi nello stesso stream SSE e nell'ordine in cui accadono. I token arrivano solo dopo l'ultimo tool,
perché il modello scrive la risposta quando ha finito di cercare. Il `no-answer` non si può decidere prima di sapere se i tool hanno trovato chunk, e a quel punto il testo è già partito: da qui `replace`.
Provarlo: `./scripts/agent-stream.sh`.

## 2. Il ciclo dei tool (cosa fa Spring AI)

Il codice dell'app non scrive nessun ciclo. Lo fa Spring AI dentro `ChatClient...call()`:

```
        ┌────────────────────────────────────────────────────────────┐
        │ prompt: system + domanda + descrizione dei tool (JSON schema)│
        └───────────────────────────┬────────────────────────────────┘
                                    ▼
                               ┌─────────┐   risposta testuale
                      ┌───────►│   LLM   │────────────────────────► fine: content()
                      │        └────┬────┘
                      │             │ "chiama searchKnowledgeBase(query=..., mode=...)"
                      │             ▼
                      │   Spring AI esegue il metodo @Tool Java
                      │   (nostra classe KnowledgeTools, stesso thread)
                      │             │ stringa di ritorno
                      └─────────────┘ rimandata al LLM come "tool result"
```

1. Spring AI legge i metodi annotati con `@Tool` e ne genera lo schema JSON (nome, `description`,
   parametri con le `@ToolParam`). Lo schema viaggia nella richiesta al modello: **le descrizioni sono
   il "manuale" del tool** e influenzano direttamente le scelte del LLM.
2. Il modello risponde con testo oppure con una *tool call* (nome + argomenti JSON).
3. In caso di tool call, Spring AI invoca il metodo Java, prende la `String` restituita, la accoda alla
   conversazione e richiama il modello. Si ripete finché il modello risponde con testo.
4. Il valore di ritorno del tool è **solo testo** per il modello: tutto ciò che vuole sapere (chunk, errori,
   "nessun risultato") deve stare in quella stringa.

Due fatti di Spring AI 1.0.7 che hanno guidato il design:

- **Nessun tetto alle iterazioni**: finché il modello emette tool call il ciclo continua.
- **Un'eccezione lanciata dal tool non ferma il ciclo**: viene convertita in testo e rimandata al modello.
  Per questo il tetto anti-loop è un messaggio, non un'eccezione (vedi §6).

## 3. Mappa del codice

| File | Ruolo |
|------|-------|
| `ApiController.java:66` | endpoint `GET /agent/search`, passa `q`, `langId`, `contentId` al service |
| `AgentSearchService.java` | prompt, ciclo `ChatClient`, i 3 tool, guardia, record di risposta |
| `SearchService.java:121` `retrieve()` | retrieval per similarità (kNN [+BM25/RRF] + soglia), **condiviso con `/search`** |
| `SearchService.java:152` `fetchChunks()` | lettura per identità: primi N chunk in ordine, senza kNN né soglia |
| `SearchService.java:167` `listDocuments()` | aggregazione su `source`: elenco documenti |
| `SearchFilters.java` | filtri sui metadati → `term`/`terms` query (riusato dai tre tool) |

## 4. Passo per passo: una richiesta

### 4.1 Controller (`ApiController.java:66`)

```java
@GetMapping("/agent/search")
public AgentSearchService.AgentResult agentSearch(@RequestParam String q,
        @RequestParam(required = false) String langId,
        @RequestParam(required = false) String contentId) {
    return agentSearchService.search(q, langId, contentId);
}
```

Nessuna logica: inoltra i parametri. `langId`/`contentId` non vanno al modello, vanno al service.

### 4.2 `AgentSearchService.search()` (`AgentSearchService.java:59`)

```java
KnowledgeTools tools = new KnowledgeTools(searchService, blankToNull(langId), blankToNull(contentId), maxToolCalls);
String answer = chatClient.prompt()
        .system(SYSTEM_PROMPT.formatted(maxToolCalls))
        .user(question)
        .tools(tools)
        .call()
        .content();
if (tools.sources.isEmpty()) {
    answer = noAnswer;
}
return new AgentResult(answer, tools.steps, new ArrayList<>(tools.sources.values()), tools.truncated);
```

- **Un `KnowledgeTools` nuovo per ogni richiesta.** Contiene lo stato della richiesta (passi eseguiti, fonti
  raccolte, contatore della guardia, filtri del chiamante). Se fosse un singleton, due richieste
  simultanee si mescolerebbero.
- `.tools(tools)` registra l'oggetto: Spring AI scansiona i suoi metodi `@Tool`.
- `.call()` è il punto in cui gira **tutto il ciclo** del §2; ritorna solo a ciclo finito.
- **Rete di sicurezza finale**: se nessun tool ha raccolto chunk (`sources` vuoto) la risposta viene
  sostituita col testo fisso `no-answer`, come in `/search`. Così il modello non può rispondere con conoscenze
  proprie quando la knowledge base non ha trovato niente. Conseguenza: anche una risposta data dopo la sola
  `listDocuments` (che non registra fonti) diventa `no-answer` se non si legge poi nessun chunk.

### 4.3 Il system prompt (`AgentSearchService.java:26`)

Le regole che il codice non può imporre, dette al modello:

- domande "di cosa parla il documento X" → **non** `searchKnowledgeBase` (la domanda non assomiglia a nessun
  chunk) ma `listDocuments` poi `getDocumentChunks`; dichiarare che il riassunto copre solo l'inizio;
- fatti specifici → `searchKnowledgeBase`, una ricerca per ogni parte della domanda;
- riformulare se non trova, entro `%d` chiamate (sostituito con `app.agent.max-tool-calls`);
- `hybrid` per codici/sigle, `semantic` per linguaggio naturale;
- **non usare filtri `topics`/`source` se l'utente non li chiede**: in test il modello si inventava `langId=it`
  e azzerava i risultati perché i documenti non avevano quel metadato;
- non usare conoscenze esterne, citare le fonti `[source#chunk]`;
- se un tool risponde "limite raggiunto" o "già eseguita", smettere e rispondere.

Il prompt **orienta**, non garantisce: il modello può non seguirlo. Per questo ciò che conta davvero
(filtri del chiamante, tetto, soglia) sta nel codice.

### 4.4 I tre tool (`KnowledgeTools`)

I tool sono metodi di una classe annidata `KnowledgeTools` (`AgentSearchService.java:75`).

#### `searchKnowledgeBase(query, mode, topics, source)` — `:120`

Ricerca per **similarità**, per fatti specifici.

```java
String blocked = guard("searchKnowledgeBase", query, query + "|" + mode + "|" + topics + "|" + source);
if (blocked != null) return blocked;
SearchMode searchMode = "hybrid".equalsIgnoreCase(mode) ? SearchMode.HYBRID : SearchMode.SEMANTIC;
SearchFilters filters = new SearchFilters(blankToNull(source), langId, contentId, topics, null);
List<ChunkHit> hits = searchService.retrieve(query, searchMode, filters);
steps.add(new ToolStep("searchKnowledgeBase", query, searchMode.name(), filters, hits.size()));
```

1. `guard(...)`: tetto e dedup (§6). Se blocca, il tool restituisce il messaggio e non cerca.
2. `mode` assente o sconosciuto → `SEMANTIC`.
3. **Costruzione dei filtri: `source` e `topics` vengono dal LLM; `langId` e `contentId` dal chiamante**
   (campi di `KnowledgeTools`). Il modello non vede quei due parametri e non può cambiarli: l'agente
   non può uscire dal perimetro che l'API ha fissato.
4. `retrieve()` è lo **stesso metodo che usa `/search`**: embed della query, kNN filtrato, soglia 0.76 sullo
   score kNN, in HYBRID anche BM25 + fusione RRF. Sotto soglia ritorna lista vuota.
5. Registra lo step e ogni chunk in `sources` (`putIfAbsent` sulla chiave `source#chunk`: niente duplicati
   se due ricerche trovano lo stesso chunk).
6. Ritorna al modello una stringa `[source#idx] testo` per chunk, oppure `"Nessun risultato pertinente."`.

Il tool "no risultati" spinge il modello a riformulare: è così che nasce il comportamento agentico.

#### `listDocuments()` — `:149`

Elenca `source`, `contentId`, `langId` e numero di chunk dei documenti **dentro il perimetro** dei
vincoli del chiamante. Serve a risolvere "il manuale della lavatrice" nel nome esatto `manuale-it`
senza indovinare. Usa `SearchService.listDocuments()` (`SearchService.java:167`): una *terms aggregation*
su `source` (max 100 bucket) con due sotto-aggregazioni da 1 bucket per `contentId` e `langId`, e
`maxResults(0)` perché i documenti non servono, solo i bucket.

#### `getDocumentChunks(source, maxChunks)` — `:171`

Lettura per **identità**, non per similarità: ritorna i primi N chunk di una `source` in ordine di
`chunkIndex`. N default 8, tetto 15 (`Math.min(..., 15)`), per non gonfiare il contesto.
Usa `SearchService.fetchChunks()` (`SearchService.java:152`): una query con solo `bool.filter`
(i filtri di `SearchFilters.toQueries()`), ordinata per `chunkIndex`, **senza kNN e senza soglia**.

Perché serve: la domanda "di cosa parla il documento palline?" non contiene alcun argomento, quindi
il suo embedding non è vicino a nessun chunk; la soglia 0.76 la scarterebbe e l'agente risponderebbe
`no-answer` anche se il documento esiste. Leggere per nome aggira il problema.

Limite noto: un documento lungo viene riassunto solo sui primi N chunk (con chunk da 800 caratteri,
8 chunk ≈ 6400 caratteri). Il prompt chiede al modello di dichiararlo.

### 4.5 Esempio di esecuzione

`GET /agent/search?q=Di cosa parla il documento palline?` (traccia reale):

```
1. LLM  -> tool call listDocuments()                       -> 6 documenti
2. LLM  -> tool call getDocumentChunks(source="palline")   -> 1 chunk
3. LLM  -> testo: "Il documento palline descrive delle palline da tennis in un cesto,
                   verdi e lucide [palline#0]."
```

`steps` mostra le prime due righe; `sources` contiene `palline#0`.

## 5. Funzioni di supporto in `SearchService`

- **`retrieve()`** (`:121`): estratto da `search()` per essere riusato. Ritorna `List<ChunkHit>`; lista
  vuota = niente sopra soglia. `search()` lo chiama e poi fa la sola parte LLM; l'agente lo chiama dal tool.
- **`fetchChunks()`** (`:152`) e **`listDocuments()`** (`:167`): descritti sopra. Come `retrieve()`, i filtri
  stanno in `bool.filter` (non influiscono sullo score).
- I campi `source`, `contentId`, `langId`, `topics`, `filename` sono `Keyword` in `ChunkDocument`: servono
  filtri esatti e aggregazioni, non full-text.

## 6. Guardia anti-loop (`AgentSearchService.java:100`)

Problema: il modello può richiamare i tool all'infinito (ricerche inutili, stessa ricerca ripetuta). Costo
in latenza e token, e sul provider free di Groq si arriva al 429 (limite token al minuto). Il tetto "massimo
4 ricerche" scritto solo nel prompt è stato superato nei test (5 ricerche).

```java
private String guard(String tool, String arg, String argsKey) {
    if (++calls > maxToolCalls) {
        return blocked(tool, arg, "Limite di chiamate raggiunto: ... rispondi ora ...");
    }
    if (!seen.add(tool + "|" + argsKey.toLowerCase().strip())) {
        return blocked(tool, arg, "Ricerca gia' eseguita con questi parametri: cambia query o rispondi.");
    }
    return null;   // la chiamata puo' procedere
}
```

Chiamata come prima istruzione di **ogni** tool.

- **Tetto** (`app.agent.max-tool-calls`, default 6, `application.yml`): oltre il tetto il tool *non esegue
  nulla* (niente Elasticsearch, niente embedding) e risponde al modello di concludere. Il contatore conta tutte
  le chiamate, anche quelle bloccate.
- **Dedup**: stessa chiamata (tool + argomenti normalizzati lowercase/strip) già vista → bloccata. È il
  caso tipico di loop: il modello ripete la stessa query.
- **Perché un messaggio e non un'eccezione**: in Spring AI 1.0.7 un'eccezione dal tool viene rimandata al
  modello come testo e il ciclo prosegue; non lo ferma.
- **`blocked()`** (`:111`): imposta `truncated = true` e registra uno step con `hits = -1`, così dall'esterno
  si vede che la guardia è scattata.

Limite: la guardia è "morbida". Un modello che ignora il messaggio può continuare a chiamare; ogni chiamata
extra però è gratuita e in pratica il modello risponde subito. Non c'è un timeout globale né un blocco
duro del ciclo: in 1.0.7 non si possono fare in modo pulito.

## 7. Garanzie e limiti, in sintesi

| Cosa | Chi lo garantisce |
|------|-------------------|
| L'agente non esce da `langId`/`contentId` | **codice** (campi di `KnowledgeTools`, il LLM non li vede) |
| Soglia di rilevanza sui chunk | **codice** (`retrieve()`) |
| Nessuna risposta senza chunk recuperati | **codice** (`sources.isEmpty()` → `no-answer`) |
| Tetto di chiamate e dedup | **codice** (guardia), con messaggio al modello |
| Quante ricerche fare e con che query | **LLM** |
| Non inventare filtri, citare le fonti | **prompt** (non garantito) |
| Il riassunto copre l'intero documento | **no**: solo i primi N chunk |

Altre cose da sapere:

- **Latenza e costo**: ogni giro del ciclo è una chiamata LLM in più rispetto a `/search` (che ne fa una).
  Una domanda semplice costa ~2 chiamate (tool call + risposta), una difficile fino a `max-tool-calls + 1`.
- **Rate limit del provider**: lanciando molti scenari di fila si può avere HTTP 429 sul TPM di Groq;
  per questo `scripts/agent-search.sh` ha la variabile `PAUSE`.
- **Variabilità**: la scelta dei tool dipende dal modello e non è deterministica. Con la stessa domanda
  il numero di ricerche può cambiare da una chiamata all'altra.
- **`temperature: 0.2`** (`application.yml`) riduce, non elimina, la variabilità.

## 8. Come provarlo

```bash
./scripts/agent-search.sh                       # 9 scenari, stampa request, response e riepilogo
PAUSE=20 ./scripts/agent-search.sh              # con pausa tra scenari (limite TPM)
./scripts/agent-search.sh "Di cosa parla il documento palline?"

curl -G localhost:8080/agent/search --data-urlencode "q=Di che colore sono le palline da tennis?"
curl -G localhost:8080/agent/search --data-urlencode "q=Di cosa parla questo documento?" \
     --data-urlencode contentId=C-palline
```

Per vedere il comportamento della guardia, abbassa il tetto:
`mvn spring-boot:run -Dspring-boot.run.arguments=--app.agent.max-tool-calls=2` e chiedi qualcosa che non è
nella knowledge base (es. "Dove si trova la Torre Eiffel?"): dopo 2 ricerche la terza compare negli `steps` con `hits: -1`
e la risposta ha `truncated: true`.

Per leggere le richieste HTTP reali verso il modello (con i tool nello schema e i tool result nei messaggi),
tieni `HTTP_LOG_ENABLED=true`: il logger `http.request` le stampa.

## 9. Come estenderlo

Per aggiungere un tool:

1. aggiungi un metodo in `KnowledgeTools` con `@Tool(description = ...)` e `@ToolParam` descrittivi
   (la descrizione è ciò che il modello legge per decidere);
2. come **prima istruzione** chiama `guard(...)` (altrimenti sfugge a tetto e dedup);
3. applica i vincoli del chiamante (`langId`, `contentId`) quando costruisci `SearchFilters`;
4. registra `steps.add(new ToolStep(...))` e, se restituisce chunk, riempi `sources`;
5. descrivi nel `SYSTEM_PROMPT` **quando** usarlo e quando no.

Idee naturali: un tool per leggere un singolo chunk adiacente (contesto prima/dopo), uno per filtrare
per `topics` con elenco dei topic esistenti, un riassunto map-reduce per documenti lunghi.
