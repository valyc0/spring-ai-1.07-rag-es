package com.example.demo;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class IngestService {

    private final EmbeddingModel embeddingModel;
    private final ElasticsearchOperations operations;
    private final int chunkSize;
    private final int overlap;
    private final int embedBatchSize;

    public IngestService(EmbeddingModel embeddingModel,
                         ElasticsearchOperations operations,
                         @Value("${app.chunk.size:800}") int chunkSize,
                         @Value("${app.chunk.overlap:100}") int overlap,
                         @Value("${app.chunk.embed-batch-size:64}") int embedBatchSize) {
        // con overlap >= size lo split non avanza mai: ciclo infinito
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "app.chunk.overlap (" + overlap + ") deve essere >= 0 e < app.chunk.size (" + chunkSize + ")");
        }
        if (embedBatchSize < 1) {
            throw new IllegalArgumentException("app.chunk.embed-batch-size deve essere >= 1");
        }
        this.embeddingModel = embeddingModel;
        this.operations = operations;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.embedBatchSize = embedBatchSize;
    }

    public int ingest(String source, String text) {
        List<String> chunks = split(text);
        if (chunks.isEmpty()) {
            return 0;
        }

        // una chiamata ogni embedBatchSize chunk: un documento grande in una sola chiamata
        // sforerebbe i limiti del provider. L'ordine dei vettori rispecchia quello dei chunk.
        List<float[]> embeddings = new ArrayList<>(chunks.size());
        for (int from = 0; from < chunks.size(); from += embedBatchSize) {
            int to = Math.min(from + embedBatchSize, chunks.size());
            embeddings.addAll(embeddingModel.embed(chunks.subList(from, to)));
        }

        List<ChunkDocument> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDocument doc = new ChunkDocument();
            // id deterministico da source + testo: reinviare lo stesso documento sovrascrive
            // i chunk invece di duplicarli, mentre testi diversi sullo stesso source convivono
            doc.setId(UUID.nameUUIDFromBytes((source + "\n" + chunks.get(i)).getBytes(StandardCharsets.UTF_8))
                    .toString());
            doc.setSource(source);
            doc.setChunkIndex(i);
            doc.setContent(chunks.get(i));
            doc.setEmbedding(embeddings.get(i));
            docs.add(doc);
        }

        operations.save(docs);
        // save() non fa refresh: senza, i chunk appena scritti non sono ricercabili
        // (ES refresca da solo ogni ~1s) e una /search subito dopo non li troverebbe
        operations.indexOps(ChunkDocument.class).refresh();
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
