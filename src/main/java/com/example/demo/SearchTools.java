package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG agentico: il retrieval è un tool che il modello può chiamare quando e come vuole,
 * invece di essere eseguito a priori come in {@link SearchService}.
 * <p>
 * <b>Non è un bean.</b> Un'istanza per ogni richiesta HTTP: {@link #usedChunks} raccoglie cosa
 * ha letto il modello durante QUELLA richiesta, e il risultato va restituito nelle
 * {@code sources} di {@link SearchService.SearchResult}. Un bean singleton sarebbe condiviso da
 * richieste concurrenti e le fonti si mescolerebbero fra utenti diversi.
 * <p>
 * Il tracciamento è thread-safe anche se un'istanza venisse riusata: le tool-call di una singola
 * richiesta sono sequenziali, ma non costa nulla esserlo.
 */
public class SearchTools {

    private static final Logger log = LoggerFactory.getLogger(SearchTools.class);

    /** Restituito al modello quando un giro di retrieval non supera la soglia. */
    private static final String NO_HITS =
            "Nessun chunk corrisponde alla ricerca. La soglia di rilevanza non e' stata superata: "
            + "non ipotizzare e non usare conoscenze esterne ai documenti.";

    private final ChunkRetriever retriever;
    private final SearchMode mode;

    /** Filtri imposti dal chiamante (/search?...): il modello non puo' toglierli, solo aggiungerne. */
    private final SearchFilters baseFilters;

    /** fonte#indice -> chunk, nell'ordine di arrivo: le sources finali mantengono l'ordine di lettura. */
    private final Map<String, ChunkRetriever.ChunkHit> used = new LinkedHashMap<>();

    /** Query distinte richieste dal modello su quest'istanza. */
    private final Set<String> queries = new LinkedHashSet<>();

    public SearchTools(ChunkRetriever retriever, SearchMode mode, SearchFilters baseFilters) {
        this.retriever = retriever;
        this.mode = mode;
        this.baseFilters = baseFilters;
    }

    /**
     * Filtro richiesto dal modello sommato a quelli del chiamante. Un campo gia' fissato dal
     * caller vince: /search?langId=it resta "solo italiano" anche se il modello passa langId=en.
     * I campi non toccati dal modello restano quelli del caller.
     */
    private SearchFilters merge(String source, String langId, List<String> topics, String filename) {
        return new SearchFilters(
                pick(baseFilters.source(), source),
                pick(baseFilters.langId(), langId),
                baseFilters.contentId(),
                baseFilters.topics() != null && !baseFilters.topics().isEmpty() ? baseFilters.topics() : topics,
                pick(baseFilters.filename(), filename));
    }

    private static String pick(String base, String fromModel) {
        return base != null && !base.isBlank() ? base : fromModel;
    }

    /** Crea il provider per registrare l'istanza come tool su un ChatClient. */
    public static MethodToolCallbackProvider provider(SearchTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    /**
     * Tool principale. Il modello decide QUANDO chiamarlo: puo' fare piu' ricerche con
     * parole chiave diverse, o rispondere subito se sa rispondere senza documenti.
     * <p>
     * I filtri sono opzionali e AND tra loro. Chi la chiama puo' passare solo 'query' e farsi
     * restituire i chunk piu' vicini in assoluto.
     */
    @Tool(description = "Cerca nei documenti indicizzati e restituisce i passaggi rilevanti, "
            + "ciascuno con la sua fonte. Usalo per ogni domanda che richieda informazioni "
            + "presenti nei documenti caricati. Puoi chiamarlo piu' volte con parole chiave "
            + "diverse per approfondire, e usare i filtri per restringere la ricerca.")
    public String searchChunks(
            @ToolParam(description = "Testo da cercare: meglio parole chiave che una frase intera") String query,
            @ToolParam(required = false, description = "Filtra per fonte esatta, es. 'doc1'") String source,
            @ToolParam(required = false, description = "Filtra per lingua, es. 'it' o 'en'") String langId,
            @ToolParam(required = false, description = "Filtra per uno o piu' topic (OR fra loro)") List<String> topics,
            @ToolParam(required = false, description = "Filtra per nome file esatto") String filename) {

        SearchFilters filters = merge(source, langId, topics, filename);
        ChunkRetriever.Retrieval r = retriever.retrieve(query, mode, filters);
        record(r, query);

        if (r.empty()) {
            // motivo esplicito: il modello deve poter distinguere "filtri troppo stretti" da
            // "il contenuto non c'e'", e nel primo caso ha senso riprovare con meno filtri
            return r.belowThreshold() ? NO_HITS
                    : "Nessun documento corrisponde ai filtri richiesti. Riprova con meno filtri o senza.";
        }

        StringBuilder sb = new StringBuilder();
        for (ChunkRetriever.ChunkHit hit : r.ranked()) {
            used.putIfAbsent(hit.citation(), hit);
            sb.append('[').append(hit.citation()).append("] ").append(hit.content()).append("\n\n");
        }
        return sb.toString().strip();
    }

    /** Chunk effettivamente letti dal modello, in ordine di prima lettura. */
    public List<ChunkRetriever.ChunkHit> usedChunks() {
        return List.copyOf(used.values());
    }

    private void record(ChunkRetriever.Retrieval r, String query) {
        queries.add(query);
        if (log.isDebugEnabled()) {
            log.debug("tool searchChunks: query={} mode={} topScore={} hits={} used={}",
                    query, mode, r.topScore(), r.ranked().size(), used.size());
        }
    }

    /** Query distinte che il modello ha passato a questo tool: quante ricerche ha fatto davvero. */
    public Set<String> searchedQueries() {
        return Set.copyOf(queries);
    }

    public int searchCount() {
        return queries.size();
    }
}