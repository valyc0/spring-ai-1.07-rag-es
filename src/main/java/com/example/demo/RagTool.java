package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Il RAG di {@link SearchService} esposto come tool del ChatClient: il modello decide <b>quando</b>
 * cercare e <b>come</b> (parole, modalita', filtri), e riceve i chunk trovati come contesto.
 * <p>
 * Il tool non chiama l'LLM: {@link SearchService#chunksAboveThreshold} finisce al retrieved e il
 * passo dopo e' dell'agente, che puo' fare altre ricerche prima di rispondere. Per questo il
 * ritorno e' testo (i chunk), non una risposta: un tool che rispondesse per conto suo costerebbe
 * una completion dentro il ciclo agentico e non lascerebbe al modello la scelta di cercare ancora.
 * <p>
 * Filtri e modalita' sono parametri del tool e non valori fissi: e' la differenza rispetto a
 * {@code /search}, dove li sceglie la richiesta HTTP. Con un agente la scelta la fa il modello in
 * base alla domanda (lingua della domanda -&gt; langId, codice errore -&gt; mode=lexical). I filtri
 * che la richiesta HTTP passa esplicitamente hanno la precedenza su quelli del modello, campo per
 * campo: chi scrive la richiesta sa quali documenti esistono, il modello no.
 * <p>
 * Ogni invocazione accoda una {@link SearchTrace} alla lista che il chiamante ha messo nel
 * {@link ToolContext}: e' cosi' la risposta HTTP mostra quante e quali ricerche ha fatto
 * l'agente, senza tenere stato nel bean (che sarebbe condiviso fra richieste concorrenti).
 */
@Component
public class RagTool {

    /** Chiave del toolContext sotto cui il chiamante lascia la lista da riempire di {@link SearchTrace}. */
    static final String TRACE_KEY = "ragToolTraces";

    /** Chiave del toolContext sotto cui il chiamante lascia i filtri imposti dalla richiesta HTTP. */
    static final String FORCED_KEY = "ragForcedFilters";

    private static final Logger log = LoggerFactory.getLogger(RagTool.class);

    private final SearchService searchService;
    private final String emptyResult;

    public RagTool(SearchService searchService,
                   @Value("${app.agent.empty-result:Nessun chunk trovato in indice su questo argomento.}") String emptyResult) {
        this.searchService = searchService;
        this.emptyResult = emptyResult;
    }

    @Tool(name = "search_knowledge_base", description = """
            Cerca nell'indice documentale i chunk che rispondono a una domanda o contengono un
            termine (per somiglianza sul vettore, per parole esatte, o in entrambi i modi).
            Restituisce i TESTI trovati con le loro fonti, non una risposta: se serve rispondere,
            falla tu con questi testi. Chiamalo piu' volte, con parole diverse o con filtri
            diversi, se la prima ricerca non porta a niente.""")
    public String search(
            @ToolParam(description = "Domanda o termine da cercare nell'indice, in lingua") String question,
            @ToolParam(required = false, description = "semantic (default, solo somiglianza), hybrid (somiglianza + parole) o lexical (solo parole esatte: codici, nomi di file)") String mode,
            @ToolParam(required = false, description = "Tieni solo i chunk di questa source esatta") String source,
            @ToolParam(required = false, description = "Tieni solo i chunk in questa lingua (es. it, en)") String langId,
            @ToolParam(required = false, description = "Tieni solo i chunk di questo contentId esatto") String contentId,
            @ToolParam(required = false, description = "Tieni solo i chunk con almeno uno di questi topic") List<String> topics,
            @ToolParam(required = false, description = "Tieni solo i chunk di questo nome file esatto") String filename,
            ToolContext toolContext) {

        // senza domanda non c'e' niente da cercare: invece di far esplodere l'embedding (che
        // finirebbe in 500 sulla richiesta dell'utente) si risponde come quando l'indice non copre
        if (question == null || question.isBlank()) {
            log.info("tool RAG chiamato senza domanda: niente da cercare");
            return emptyResult;
        }

        SearchMode searchMode = modeOrDefault(mode);
        // i filtri della richiesta HTTP hanno la precedenza su quelli scelti dal modello:
        // chi scrive la richiesta sa quali documenti esistono, il modello no
        SearchFilters filters = forcedFilters(toolContext)
                .merge(new SearchFilters(source, langId, contentId, cleanTopics(topics), filename));
        List<SearchService.ChunkHit> chunks =
                searchService.chunksAboveThreshold(question, searchMode, filters);
        trace(toolContext, new SearchTrace(question, searchMode.name().toLowerCase(Locale.ROOT),
                filtersSummary(filters), chunks.size()));

        if (chunks.isEmpty()) {
            return emptyResult;
        }
        return formatContext(chunks, searchMode);
    }

    /**
     * Contesto per l'agente: la stessa intestazione "[source#chunkIndex] testo" che usa
     * {@link SearchService#answerFor}, cosi' il prompt vale anche per l'agente, piu' la riga delle
     * fonti con gli score e i metadati del documento. Lo score serve all'agente per distinguere un
     * chunk forte da uno debole, il contentId per dire "secondo il manuale X" e la lingua per capire
     * se il testo e' quello che gli serve.
     */
    static String formatContext(List<SearchService.ChunkHit> chunks, SearchMode mode) {
        StringBuilder context = new StringBuilder()
                .append("modalita' ").append(mode.name().toLowerCase(Locale.ROOT))
                .append(", ").append(chunks.size()).append(chunks.size() == 1 ? " chunk trovato" : " chunk trovati")
                .append(".\n\n");
        for (SearchService.ChunkHit hit : chunks) {
            context.append("[").append(hit.source()).append("#").append(hit.chunkIndex()).append("] ")
                    .append(hit.content()).append("\n\n");
        }
        context.append("Fonti: ")
                .append(chunks.stream().map(RagTool::sourceLine).collect(Collectors.joining(", ")))
                .append("\n");
        return context.toString();
    }

    /** "source#chunkIndex (score 0.912, contentId C-100, langId it)", solo metadati presenti. */
    private static String sourceLine(SearchService.ChunkHit hit) {
        StringBuilder line = new StringBuilder()
                .append(hit.source()).append('#').append(hit.chunkIndex())
                .append(String.format(Locale.ROOT, " (score %.3f", hit.score()));
        if (notBlank(hit.contentId())) {
            line.append(", contentId ").append(hit.contentId());
        }
        if (notBlank(hit.langId())) {
            line.append(", langId ").append(hit.langId());
        }
        return line.append(')').toString();
    }

    /**
     * Filtri usati, in forma compatta: "langId=it, topics=[errori, cucina]", "-" se nessuno.
     * Valgono le stesse regole di {@link SearchFilters}: un valore vuoto e' un filtro assente,
     * quindi viene contato solo se il campo e' valorizzato per davvero.
     */
    static String filtersSummary(SearchFilters filters) {
        List<String> parts = new ArrayList<>();
        addFilter(parts, "source", filters.source());
        addFilter(parts, "langId", filters.langId());
        addFilter(parts, "contentId", filters.contentId());
        if (filters.topics() != null && !filters.topics().isEmpty()) {
            parts.add("topics=" + filters.topics());
        }
        addFilter(parts, "filename", filters.filename());
        return parts.isEmpty() ? "-" : String.join(", ", parts);
    }

    private static void addFilter(List<String> parts, String name, String value) {
        if (notBlank(value)) {
            parts.add(name + "=" + value);
        }
    }

    /**
     * Topic valorizzati, senza vuoti: una lista di sole stringhe bianche (o null) diventa null,
     * cioe' nessun filtro. Serve perche' {@code SearchFilters} prende i topic come sono e una
     * terms query con "" non matcherebbe nulla: il modello che risponde "topics non applicabili"
     * con [""] deve comunque vedere i chunk trovati senza quel filtro.
     */
    static List<String> cleanTopics(List<String> topics) {
        if (topics == null) {
            return null;
        }
        List<String> valorizzati = topics.stream().filter(RagTool::notBlank).toList();
        return valorizzati.isEmpty() ? null : valorizzati;
    }

    /**
     * Modalita' richiesta dal modello, con fallback su SEMANTIC se manca o non e' riconosciuta:
     * il tool non puo' rispondere 400 come fa {@code /search} su un parametro che il modello ha
     * inventato, quindi una modalita' inesistente degrada invece di far fallire la richiesta.
     */
    static SearchMode modeOrDefault(String mode) {
        if (mode == null || mode.isBlank()) {
            return SearchMode.SEMANTIC;
        }
        try {
            return SearchMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.info("modalita' non riconosciuta dal modello, uso il default: {}", mode);
            return SearchMode.SEMANTIC;
        }
    }

    /**
     * Filtri imposti dalla richiesta HTTP, se il chiamante li ha messi nel toolContext: arrivano
     * per richiesta, quindi il bean resta senza stato (vedi {@link #FORCED_KEY}). Se mancano,
     * valgono come assenti e i filtri del modello passano come sono.
     */
    private static SearchFilters forcedFilters(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext().get(FORCED_KEY) instanceof SearchFilters forced) {
            return forced;
        }
        return new SearchFilters(null, null, null, null, null);
    }

    /**
     * Accoda la trace nella lista del toolContext, se il chiamante l'ha lasciata: la lista e' un
     * valore della mappa, che {@code DefaultToolCallingManager} copia ma non clona, quindi la
     * scrittura la vede anche il chiamante. Senza lista (test, o chiamata senza toolContext)
     * la trace semplicemente non viene registrata e il tool lavora lo stesso.
     */
    @SuppressWarnings("unchecked")
    private static void trace(ToolContext toolContext, SearchTrace trace) {
        if (toolContext == null) {
            return;
        }
        Object traces = toolContext.getContext().get(TRACE_KEY);
        if (traces instanceof List) {
            ((List<SearchTrace>) traces).add(trace);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Una ricerca fatta dall'agente: cosa ha chiesto e quanti chunk ha trovato (0 = soglia respinta). */
    public record SearchTrace(String question, String mode, String filters, int chunks) {}
}
