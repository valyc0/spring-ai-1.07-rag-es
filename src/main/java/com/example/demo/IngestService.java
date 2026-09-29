package com.example.demo;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class IngestService {

    private final EmbeddingModel embeddingModel;
    private final ElasticsearchOperations operations;
    private final int chunkSize;
    private final int overlap;

    public IngestService(EmbeddingModel embeddingModel,
                         ElasticsearchOperations operations,
                         @Value("${app.chunk.size:800}") int chunkSize,
                         @Value("${app.chunk.overlap:100}") int overlap) {
        this.embeddingModel = embeddingModel;
        this.operations = operations;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public int ingest(String source, String text) {
        List<String> chunks = split(text);
        if (chunks.isEmpty()) {
            return 0;
        }

        // una sola chiamata per tutti i chunk: l'ordine dei vettori rispecchia quello dei chunk
        List<float[]> embeddings = embeddingModel.embed(chunks);

        List<ChunkDocument> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDocument doc = new ChunkDocument();
            doc.setId(UUID.randomUUID().toString());
            doc.setSource(source);
            doc.setChunkIndex(i);
            doc.setContent(chunks.get(i));
            doc.setEmbedding(embeddings.get(i));
            docs.add(doc);
        }

        operations.save(docs);
        return docs.size();
    }

    private List<String> split(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
            if (end == text.length()) {
                break;
            }
            start = end - overlap;
        }
        return result;
    }
}
