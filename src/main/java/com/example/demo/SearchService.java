package com.example.demo;

import com.example.demo.ChunkRetriever.ChunkHit;
import com.example.demo.ChunkRetriever.Retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG: embed della domanda -> retrieval filtrato sui metadati -> soglia -> contesto -> LLM.
 * <p>
 * Due percorsi sullo stesso retrieval ({@link ChunkRetriever}):
 * <ul>
 *   <li>{@link #search} — RAG classico: UN giro di retrieval deciso dall'app, il contesto finisce
 *       nel prompt e l'LLM risponde su quello. Prevedibile, una sola chiamata al provider.</li>
 *   <li>{@link #searchAgentic} — RAG agentico: il retrieval è un tool
 *       ({@link SearchTools}) e decide il modello quando e con quali parole chiave cercare,
 *       anche piu' volte. Piu' adattivo, ma piu' chiamate e piu' latenza.</li>
 * </ul>
 * In entrambi i filtri stanno DENTRO le query (knn.filter e bool.filter), non in post_filter:
 * cosi' ES cerca i vicini solo tra i chunk che passano i filtri e restituisce comunque k risultati.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private static final String SYSTEM_PROMPT = """
            Rispondi alla domanda usando SOLO il contesto fornito.
            Non usare conoscenze esterne al contesto e non inventare.
            Cita la fonte (tra parentesi quadre) quando usi un informazione dal contesto.
            """;

    /**
     * Nell'agentico NON c'e' piu' un contesto nel prompt: il modello deve sapere che il primo
     * gesto e' cercare, e che la ricerca puo' non tornare nulla senza che questo significhi
     * "rispondi comunque".
     */
    private static final String SYSTEM_PROMPT_AGENTIC = """
            Rispondi alle domande sui documenti indicizzati.

            Usa SEMPRE il tool searchChunks per ottenere i passaggi: non rispondere dal tuo
            addestramento quando la domanda riguarda i documenti caricati.
            Non usare conoscenze esterne a cio' che il tool restituisce e non inventare.
            Puoi chiamare il tool piu' volte con parole chiave diverse per approfondire.

            Cita la fonte come [fonte#indice], usando esattamente il riferimento ricevuto.
            Se il tool non restituisce nulla di utile, rispondi che non hai l'informazione.
            """;

    private final ChunkRetriever retriever;
    private final ChatClient chatClient;
    private final String noAnswer;
    private final float minScore;

    public SearchService(ChunkRetriever retriever,
                         ChatClient.Builder chatClientBuilder,
                         @Value("${app.search.min-score:0.76}") float minScore,
                         @Value("${app.search.no-answer:Non ho informazioni a riguardo.}") String noAnswer) {
        this.retriever = retriever;
        this.chatClient = chatClientBuilder.build();
        this.minScore = minScore;
        this.noAnswer = noAnswer;
    }

    /** RAG classico: un solo giro di retrieval, deciso dall'app, e il contesto va nel prompt. */
    public SearchResult search(String question, SearchMode mode, SearchFilters filters) {
        Retrieval r = retriever.retrieve(question, mode, filters);

        // sotto soglia la risposta e' fissa e NON passa dal LLM: deterministica e senza costo.
        // Vale anche in agentico, dove e' il tool a segnalarlo (vedi SearchTools.NO_HITS).
        if (r.empty()) {
            return new SearchResult(noAnswer, mode, List.of(), 0);
        }

        List<ChunkHit> ranked = r.ranked();

        // contesto per il LLM
        StringBuilder context = new StringBuilder();
        for (ChunkHit hit : ranked) {
            context.append('[').append(hit.citation()).append("] ")
                    .append(hit.content()).append("\n\n");
        }

        String answer = chatClient.prompt()
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

        return new SearchResult(answer, mode, ranked, ranked.size());
    }

    /**
     * RAG agentico: il retrieval e' un tool e decide il modello.
     * <p>
     * Il {@link SearchTools} e' creato QUI, non come bean: {@code usedChunks()} raccoglie cosa ha
     * letto il modello durante questa richiesta e va risposto nelle sources, quindi due richieste
     * concurrenti non devono condividere la stessa istanza.
     * <p>
     * Nota sul costo: {@code .tools(...)} non e' una chiamata sola. Se il modello decide di fare
     * piu' ricerche, ogni giro e' un nuovo embed + una nuova chiamata al provider, e la
     * cronologia (con i chunk dentro) viene rispedita. Controlla il timing con search.sh.
     */
    public SearchResult searchAgentic(String question, SearchMode mode, SearchFilters filters) {
        SearchTools tools = new SearchTools(retriever, mode, filters);

        // I filtri del caller sono imposti all'istanza e non sono rinegoziabili dal modello:
        // /search?langId=it deve restare "solo italiano" anche se il modello passa langId=en.
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT_AGENTIC)
                .user(u -> u.text("""
                        {question}

                        Filtri gia' applicati alla ricerca (non devi ripeterli): {filters}
                        """)
                        .param("question", question)
                        .param("filters", describeFilters(filters)))
                .tools(tools)
                .call()
                .content();

        List<ChunkHit> used = tools.usedChunks();
        log.info("agentic: ricerche={} chunk usati={} -> {}", tools.searchCount(), used.size(), question);

        // Nessun tool-call = il modello ha risposto senza guardare i documenti. Se la domanda
        // riguardava i documenti, e' inventato: meglio la risposta fissa di prima.
        if (used.isEmpty()) {
            log.warn("agentic: nessun chunk letto dal modello, risposta non ancorata ai documenti");
            return new SearchResult(noAnswer, mode, List.of(), 0);
        }
        return new SearchResult(answer, mode, used, used.size());
    }

    /** Ricorda al modello quali filtri sono gia' attivi, cosi' non li passa due volte al tool. */
    private static String describeFilters(SearchFilters f) {
        List<String> parts = new java.util.ArrayList<>();
        if (f.source() != null && !f.source().isBlank()) parts.add("source=" + f.source());
        if (f.langId() != null && !f.langId().isBlank()) parts.add("langId=" + f.langId());
        if (f.contentId() != null && !f.contentId().isBlank()) parts.add("contentId=" + f.contentId());
        if (f.topics() != null && !f.topics().isEmpty()) parts.add("topics=" + f.topics());
        if (f.filename() != null && !f.filename().isBlank()) parts.add("filename=" + f.filename());
        return parts.isEmpty() ? "nessuno" : String.join(", ", parts);
    }

    public record SearchResult(String answer, SearchMode mode, List<ChunkHit> sources, int chunksUsed) {}
}
