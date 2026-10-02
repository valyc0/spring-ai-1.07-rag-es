package com.example.demo;

import com.example.demo.SearchService.SearchResult;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class ApiController {

    private final ChatClient chatClient;
    private final IngestService ingestService;
    private final SearchService searchService;

    public ApiController(ChatClient.Builder builder, IngestService ingestService, SearchService searchService) {
        this.chatClient = builder.build();
        this.ingestService = ingestService;
        this.searchService = searchService;
    }

    @GetMapping("/chat")
    public Map<String, String> chat(@RequestParam String q) {
        String answer = chatClient.prompt().user(q).call().content();
        return Map.of("answer", answer);
    }

    /**
     * RAG con filtri opzionali sui metadati. topic si ripete per piu' valori (OR):
     * /search?q=...&mode=hybrid&langId=it&topic=sport&topic=tennis
     * <p>
     * {@code agentic=true} cambia il percorso: il retrieval diventa un tool e decide il modello
     * ({@link SearchService#searchAgentic}) invece di girare una volta sola a priori
     * ({@link SearchService#search}). Piu' ricerche, quindi piu' chiamate al provider e piu'
     * latenza: e' una scelta per valutare, non il default.
     */
    @GetMapping("/search")
    public SearchResult search(@RequestParam String q,
                               @RequestParam(defaultValue = "semantic") String mode,
                               @RequestParam(required = false) String source,
                               @RequestParam(required = false) String langId,
                               @RequestParam(required = false) String contentId,
                               @RequestParam(name = "topic", required = false) List<String> topics,
                               @RequestParam(required = false) String filename,
                               @RequestParam(defaultValue = "false") boolean agentic) {
        SearchMode searchMode;
        try {
            searchMode = SearchMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode deve essere semantic o hybrid");
        }
        SearchFilters filters = new SearchFilters(source, langId, contentId, topics, filename);
        return agentic
                ? searchService.searchAgentic(q, searchMode, filters)
                : searchService.search(q, searchMode, filters);
    }

    /** Ingest con metadati: body JSON {source, text, langId, contentId, topics[], filename}. */
    @PostMapping(path = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ingest(@RequestBody IngestRequest request) {
        if (request.source() == null || request.source().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source e' obbligatorio");
        }
        int n = ingestService.ingest(request);
        return Map.of("source", request.source(), "chunksIndexed", n);
    }

    /** Ingest senza metadati: testo grezzo nel body, source in query string (usato dagli script). */
    @PostMapping(path = "/ingest", consumes = MediaType.TEXT_PLAIN_VALUE)
    public Map<String, Object> ingestText(@RequestParam String source, @RequestBody String text) {
        return ingest(new IngestRequest(source, text, null, null, null, null));
    }
}
