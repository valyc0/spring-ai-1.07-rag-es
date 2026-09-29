package com.example.demo;

import com.example.demo.SearchService.SearchResult;
import com.example.demo.SearchService.GroupedSearchResult;

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
     */
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
        return searchService.searchGrouped(q, parseMode(mode),
                new SearchFilters(source, langId, contentId, topics, filename), answer);
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
