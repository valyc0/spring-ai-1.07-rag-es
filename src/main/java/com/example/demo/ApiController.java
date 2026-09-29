package com.example.demo;

import com.example.demo.SearchService.SearchResult;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/search")
    public SearchResult search(@RequestParam String q) {
        return searchService.search(q);
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestParam String source, @RequestBody String text) {
        int n = ingestService.ingest(source, text);
        return Map.of("source", source, "chunksIndexed", n);
    }
}
