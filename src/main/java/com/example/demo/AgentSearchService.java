package com.example.demo;

import com.example.demo.SearchService.ChunkHit;
import com.example.demo.SearchService.DocumentInfo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ricerca agentica: invece del flusso fisso di /search (un retrieval, una risposta) il LLM
 * riceve un tool di ricerca e decide da solo quante volte chiamarlo, con quale query, modalita'
 * e filtri. Puo' riformulare, scomporre una domanda in piu' ricerche o rispondere senza cercare.
 * Il loop tool-call -> risultato -> LLM lo esegue Spring AI dentro {@code call()}.
 */
@Service
public class AgentSearchService {

    private static final String SYSTEM_PROMPT = """
            Sei un assistente che risponde usando SOLO la knowledge base, accessibile coi tool
            searchKnowledgeBase, listDocuments e getDocumentChunks.
            - Domande su DI COSA PARLA / riassunto / contenuto di un documento: NON usare searchKnowledgeBase
              (la domanda non assomiglia a nessun chunk). Se il nome esatto della source non e' certo chiama
              listDocuments, poi getDocumentChunks. Se il documento non c'e', dillo.
              Un riassunto basato sui primi chunk copre solo l'inizio del documento: dichiaralo.
            - Per domande su fatti specifici usa searchKnowledgeBase. Cerca prima di rispondere. Se la domanda ha piu' parti, fai una ricerca per parte.
            - Se una ricerca non trova nulla o poco, riformula (sinonimi, termini piu' specifici) e riprova,
              al massimo %d chiamate ai tool in totale.
            - mode=hybrid per codici, nomi propri, sigle; mode=semantic per domande in linguaggio naturale.
            - NON usare filtri (topics, source) a meno che l'utente li chieda esplicitamente: i documenti
              possono non avere quei metadati e un filtro inventato azzera i risultati.
            - Non usare conoscenze esterne e non inventare: se dopo le ricerche mancano informazioni, dillo.
            - Se un tool risponde 'limite raggiunto' o 'gia' eseguita', smetti di chiamare tool e rispondi
              con quello che hai (o di' che le informazioni mancano).
            - Cita la fonte (tra parentesi quadre) per ogni informazione.
            """;

    private final ChatClient chatClient;
    private final SearchService searchService;
    private final String noAnswer;
    private final int maxToolCalls;

    public AgentSearchService(ChatClient.Builder builder, SearchService searchService,
                              @Value("${app.search.no-answer:Non ho informazioni a riguardo.}") String noAnswer,
                              @Value("${app.agent.max-tool-calls:6}") int maxToolCalls) {
        this.chatClient = builder.build();
        this.searchService = searchService;
        this.noAnswer = noAnswer;
        this.maxToolCalls = maxToolCalls;
    }

    private KnowledgeTools newTools(String langId, String contentId, Consumer<ToolStep> onStep) {
        return new KnowledgeTools(searchService, blankToNull(langId), blankToNull(contentId), maxToolCalls, onStep);
    }

    /**
     * Versione streaming (SSE). Eventi: {@code step} (ogni chiamata a un tool, appena avviene),
     * {@code token} (pezzi di testo della risposta), {@code replace} (solo se nessun chunk e' stato
     * recuperato: il testo gia' streamato va sostituito con no-answer), {@code done} (fonti e truncated),
     * {@code error}. I token arrivano solo dopo l'ultimo tool: durante le ricerche il client vede gli step.
     */
    public Flux<ServerSentEvent<Object>> stream(String question, String langId, String contentId) {
        Sinks.Many<ServerSentEvent<Object>> out = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.EmitFailureHandler retry = Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1));
        // i tool girano su thread del LLM client, non serializzati: busyLooping gestisce l'emissione concorrente
        KnowledgeTools tools = newTools(langId, contentId, step -> out.emitNext(event("step", step), retry));

        chatClient.prompt()
                .system(SYSTEM_PROMPT.formatted(maxToolCalls))
                .user(question)
                .tools(tools)
                .stream()
                .content()
                .subscribe(
                        token -> out.emitNext(event("token", token), retry),
                        e -> {
                            out.emitNext(event("error", String.valueOf(e.getMessage())), retry);
                            out.emitComplete(retry);
                        },
                        () -> {
                            if (tools.sources.isEmpty()) {
                                out.emitNext(event("replace", noAnswer), retry);
                            }
                            out.emitNext(event("done", new Done(new ArrayList<>(tools.sources.values()), tools.truncated)), retry);
                            out.emitComplete(retry);
                        });
        return out.asFlux();
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    public AgentResult search(String question, String langId, String contentId) {
        // un tool nuovo per richiesta: raccoglie passi e fonti di QUESTA richiesta senza stato condiviso
        KnowledgeTools tools = newTools(langId, contentId, step -> {});
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
    }

    /** Il tool esposto al LLM. I metodi @Tool sono invocati da Spring AI sul thread della richiesta. */
    static class KnowledgeTools {

        private final SearchService searchService;
        // vincoli decisi dal chiamante: applicati a ogni ricerca, il LLM non li vede ne' li puo' cambiare
        private final String langId;
        private final String contentId;
        private final int maxToolCalls;
        private final Consumer<ToolStep> onStep;
        private final java.util.Set<String> seen = new java.util.HashSet<>();
        private int calls;
        boolean truncated;
        final List<ToolStep> steps = new ArrayList<>();
        final Map<String, ChunkHit> sources = new LinkedHashMap<>();

        KnowledgeTools(SearchService searchService, String langId, String contentId, int maxToolCalls,
                       Consumer<ToolStep> onStep) {
            this.maxToolCalls = maxToolCalls;
            this.onStep = onStep;
            this.searchService = searchService;
            this.langId = langId;
            this.contentId = contentId;
        }

        /** Registra lo step e lo notifica a chi ascolta (lo streaming lo manda subito al client). */
        private void record(ToolStep step) {
            steps.add(step);
            onStep.accept(step);
        }

        /**
         * Guardia anti-loop. Spring AI 1.0.7 non limita le iterazioni e un'eccezione dal tool non
         * ferma il ciclo (verrebbe rimandata al LLM come testo): per questo il tetto e' un messaggio.
         * Restituisce il messaggio da dare al LLM se la chiamata va bloccata, null se puo' procedere.
         */
        private String guard(String tool, String arg, String argsKey) {
            if (++calls > maxToolCalls) {
                return blocked(tool, arg, "Limite di chiamate raggiunto: non cercare altro, rispondi ora con le "
                        + "informazioni gia' ottenute oppure di' che mancano.");
            }
            if (!seen.add(tool + "|" + argsKey.toLowerCase().strip())) {
                return blocked(tool, arg, "Ricerca gia' eseguita con questi parametri: cambia query o rispondi.");
            }
            return null;
        }

        private String blocked(String tool, String arg, String message) {
            truncated = true;
            record(new ToolStep(tool, arg, "-", new SearchFilters(null, langId, contentId, null, null), -1));
            return message;
        }

        @Tool(description = """
                Cerca nella knowledge base e restituisce i chunk piu' rilevanti, ciascuno con [fonte#indice].
                Restituisce un messaggio di 'nessun risultato' se nulla e' abbastanza pertinente.""")
        String searchKnowledgeBase(
                @ToolParam(description = "Query di ricerca autosufficiente, non la domanda grezza dell'utente") String query,
                @ToolParam(description = "semantic (significato) oppure hybrid (significato + parole esatte)", required = false) String mode,
                @ToolParam(description = "Lascia VUOTO salvo richiesta esplicita dell'utente. Filtra per topic (basta uno dei valori)", required = false) List<String> topics,
                @ToolParam(description = "Lascia VUOTO salvo richiesta esplicita dell'utente. Filtra per source esatta", required = false) String source) {
            String blocked = guard("searchKnowledgeBase", query, query + "|" + mode + "|" + topics + "|" + source);
            if (blocked != null) {
                return blocked;
            }
            SearchMode searchMode = "hybrid".equalsIgnoreCase(mode) ? SearchMode.HYBRID : SearchMode.SEMANTIC;
            SearchFilters filters = new SearchFilters(blankToNull(source), langId, contentId, topics, null);

            List<ChunkHit> hits = searchService.retrieve(query, searchMode, filters);
            record(new ToolStep("searchKnowledgeBase", query, searchMode.name(), filters, hits.size()));
            if (hits.isEmpty()) {
                return "Nessun risultato pertinente.";
            }
            StringBuilder out = new StringBuilder();
            for (ChunkHit hit : hits) {
                sources.putIfAbsent(hit.source() + "#" + hit.chunkIndex(), hit);
                out.append("[").append(hit.source()).append("#").append(hit.chunkIndex()).append("] ")
                        .append(hit.content()).append("\n\n");
            }
            return out.toString().strip();
        }

        @Tool(description = """
                Elenca i documenti disponibili (source, contentId, langId, numero di chunk).
                Usalo per trovare il nome esatto di un documento prima di leggerlo.""")
        String listDocuments() {
            String blocked = guard("listDocuments", "-", "-");
            if (blocked != null) {
                return blocked;
            }
            SearchFilters filters = new SearchFilters(null, langId, contentId, null, null);
            List<DocumentInfo> docs = searchService.listDocuments(filters);
            record(new ToolStep("listDocuments", "-", "-", filters, docs.size()));
            if (docs.isEmpty()) {
                return "Nessun documento disponibile.";
            }
            StringBuilder out = new StringBuilder();
            for (DocumentInfo d : docs) {
                out.append("- source=").append(d.source()).append(" contentId=").append(d.contentId())
                        .append(" langId=").append(d.langId()).append(" chunks=").append(d.chunks()).append("\n");
            }
            return out.toString().strip();
        }

        @Tool(description = """
                Legge un documento in ordine, dal primo chunk, senza ricerca per similarita'.
                Per domande su di cosa parla un documento. Restituisce i primi maxChunks chunk [fonte#indice].""")
        String getDocumentChunks(
                @ToolParam(description = "Nome esatto della source (vedi listDocuments)") String source,
                @ToolParam(description = "Quanti chunk leggere, default 8, massimo 15", required = false) Integer maxChunks) {
            String blocked = guard("getDocumentChunks", source, source + "|" + maxChunks);
            if (blocked != null) {
                return blocked;
            }
            int limit = Math.max(1, Math.min(maxChunks == null ? 8 : maxChunks, 15));
            SearchFilters filters = new SearchFilters(blankToNull(source), langId, contentId, null, null);
            List<ChunkHit> hits = searchService.fetchChunks(filters, limit);
            record(new ToolStep("getDocumentChunks", source, "-", filters, hits.size()));
            if (hits.isEmpty()) {
                return "Documento non trovato: controlla il nome con listDocuments.";
            }
            StringBuilder out = new StringBuilder();
            for (ChunkHit hit : hits) {
                sources.putIfAbsent(hit.source() + "#" + hit.chunkIndex(), hit);
                out.append("[").append(hit.source()).append("#").append(hit.chunkIndex()).append("] ")
                        .append(hit.content()).append("\n\n");
            }
            return out.toString().strip();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Una chiamata del LLM a un tool: quale, con che argomento e quanti risultati ha ottenuto. */
    public record ToolStep(String tool, String query, String mode, SearchFilters filters, int hits) {}

    /** truncated = true se la guardia anti-loop ha bloccato almeno una chiamata (hits=-1 negli steps). */
    public record AgentResult(String answer, List<ToolStep> steps, List<ChunkHit> sources, boolean truncated) {}

    /** Evento finale dello streaming: fonti usate e se la guardia anti-loop e' scattata. */
    public record Done(List<ChunkHit> sources, boolean truncated) {}
}
