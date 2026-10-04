package com.example.demo;

import com.example.demo.SearchService.ChunkHit;
import com.example.demo.SearchService.DocumentInfo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
              al massimo 4 ricerche in totale.
            - mode=hybrid per codici, nomi propri, sigle; mode=semantic per domande in linguaggio naturale.
            - NON usare filtri (topics, source) a meno che l'utente li chieda esplicitamente: i documenti
              possono non avere quei metadati e un filtro inventato azzera i risultati.
            - Non usare conoscenze esterne e non inventare: se dopo le ricerche mancano informazioni, dillo.
            - Cita la fonte (tra parentesi quadre) per ogni informazione.
            """;

    private final ChatClient chatClient;
    private final SearchService searchService;
    private final String noAnswer;

    public AgentSearchService(ChatClient.Builder builder, SearchService searchService,
                              @Value("${app.search.no-answer:Non ho informazioni a riguardo.}") String noAnswer) {
        this.chatClient = builder.build();
        this.searchService = searchService;
        this.noAnswer = noAnswer;
    }

    public AgentResult search(String question, String langId, String contentId) {
        // un tool nuovo per richiesta: raccoglie passi e fonti di QUESTA richiesta senza stato condiviso
        KnowledgeTools tools = new KnowledgeTools(searchService, blankToNull(langId), blankToNull(contentId));
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(question)
                .tools(tools)
                .call()
                .content();
        if (tools.sources.isEmpty()) {
            answer = noAnswer;
        }
        return new AgentResult(answer, tools.steps, new ArrayList<>(tools.sources.values()));
    }

    /** Il tool esposto al LLM. I metodi @Tool sono invocati da Spring AI sul thread della richiesta. */
    static class KnowledgeTools {

        private final SearchService searchService;
        // vincoli decisi dal chiamante: applicati a ogni ricerca, il LLM non li vede ne' li puo' cambiare
        private final String langId;
        private final String contentId;
        final List<ToolStep> steps = new ArrayList<>();
        final Map<String, ChunkHit> sources = new LinkedHashMap<>();

        KnowledgeTools(SearchService searchService, String langId, String contentId) {
            this.searchService = searchService;
            this.langId = langId;
            this.contentId = contentId;
        }

        @Tool(description = """
                Cerca nella knowledge base e restituisce i chunk piu' rilevanti, ciascuno con [fonte#indice].
                Restituisce un messaggio di 'nessun risultato' se nulla e' abbastanza pertinente.""")
        String searchKnowledgeBase(
                @ToolParam(description = "Query di ricerca autosufficiente, non la domanda grezza dell'utente") String query,
                @ToolParam(description = "semantic (significato) oppure hybrid (significato + parole esatte)", required = false) String mode,
                @ToolParam(description = "Lascia VUOTO salvo richiesta esplicita dell'utente. Filtra per topic (basta uno dei valori)", required = false) List<String> topics,
                @ToolParam(description = "Lascia VUOTO salvo richiesta esplicita dell'utente. Filtra per source esatta", required = false) String source) {
            SearchMode searchMode = "hybrid".equalsIgnoreCase(mode) ? SearchMode.HYBRID : SearchMode.SEMANTIC;
            SearchFilters filters = new SearchFilters(blankToNull(source), langId, contentId, topics, null);

            List<ChunkHit> hits = searchService.retrieve(query, searchMode, filters);
            steps.add(new ToolStep("searchKnowledgeBase", query, searchMode.name(), filters, hits.size()));
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
            SearchFilters filters = new SearchFilters(null, langId, contentId, null, null);
            List<DocumentInfo> docs = searchService.listDocuments(filters);
            steps.add(new ToolStep("listDocuments", "-", "-", filters, docs.size()));
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
            int limit = Math.max(1, Math.min(maxChunks == null ? 8 : maxChunks, 15));
            SearchFilters filters = new SearchFilters(blankToNull(source), langId, contentId, null, null);
            List<ChunkHit> hits = searchService.fetchChunks(filters, limit);
            steps.add(new ToolStep("getDocumentChunks", source, "-", filters, hits.size()));
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

    public record AgentResult(String answer, List<ToolStep> steps, List<ChunkHit> sources) {}
}
