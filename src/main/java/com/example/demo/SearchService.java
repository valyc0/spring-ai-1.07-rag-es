package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG completo: embed della domanda -> kNN su Elasticsearch -> contesto -> risposta LLM.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private static final String SYSTEM_PROMPT = """
            Rispondi alla domanda usando SOLO il contesto fornito.
            Non usare conoscenze esterne al contesto e non inventare.
            Cita la fonte (tra parentesi quadre) quando usi un informazione dal contesto.
            """;

    private final EmbeddingModel embeddingModel;
    private final ElasticsearchOperations operations;
    private final ChatClient chatClient;
    private final int topK;
    private final int numCandidates;
    private final float minScore;
    private final String noAnswer;

    public SearchService(EmbeddingModel embeddingModel,
                         ElasticsearchOperations operations,
                         ChatClient.Builder chatClientBuilder,
                         @Value("${app.search.top-k:5}") int topK,
                         @Value("${app.search.num-candidates:50}") int numCandidates,
                         @Value("${app.search.min-score:1.76}") float minScore,
                         @Value("${app.search.no-answer:Non ho informazioni a riguardo.}") String noAnswer) {
        this.embeddingModel = embeddingModel;
        this.operations = operations;
        this.chatClient = chatClientBuilder.build();
        this.topK = topK;
        this.numCandidates = numCandidates;
        this.minScore = minScore;
        this.noAnswer = noAnswer;
    }

    public SearchResult search(String question) {
        // 1. embed della domanda (stesso modello usato in ingest, altrimenti i vettori non confrontabili)
        float[] queryVector = embeddingModel.embed(question);
        // 2. kNN: i chunk piu' vicini alla domanda
        //    operations.save() non fa refresh (vedi nota 6 del README): senza refresh
        //    un ingest appena fatto non e' ricercabile e si perderebbero i chunk.
        operations.indexOps(ChunkDocument.class).refresh();

        NativeQuery knn = NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withKnnSearches(s -> s
                        .field("embedding")
                        .queryVector(toFloatList(queryVector))
                        .k(topK)
                        .numCandidates(numCandidates))
                .withMaxResults(topK)
                .build();

        SearchHits<ChunkDocument> hits = operations.search(knn, ChunkDocument.class);
        List<SearchHit<ChunkDocument>> matchers = hits.getSearchHits();

        // 3. soglia di rilevanza: sotto questa soglia il chunk piu' vicino non parla
        //    davvero della domanda, e chiedere al LLM rischierebbe che inventi.
        //    La risposta e' fissa e NON passa dal LLM: deterministica e senza costo.
        if (matchers.isEmpty() || matchers.get(0).getScore() < minScore) {
            float top = matchers.isEmpty() ? 0f : matchers.get(0).getScore();
            log.info("nessun chunk sopra la soglia: top={} minScore={} -> {}", top, minScore, question);
            return new SearchResult(noAnswer, List.of(), 0);
        }

        // 4. contesto per il LLM
        StringBuilder context = new StringBuilder();
        for (SearchHit<ChunkDocument> hit : matchers) {
            ChunkDocument doc = hit.getContent();
            context.append("[").append(doc.getSource()).append("#")
                    .append(doc.getChunkIndex()).append("] ")
                    .append(doc.getContent()).append("\n\n");
        }

        // 5. risposta
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

        List<ChunkHit> sources = matchers.stream()
                .map(hit -> new ChunkHit(
                        hit.getContent().getSource(),
                        hit.getContent().getChunkIndex(),
                        hit.getScore(),
                        hit.getContent().getContent()))
                .collect(Collectors.toList());

        return new SearchResult(answer, sources, matchers.size());
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    public record ChunkHit(String source, int chunkIndex, float score, String content) {}

    public record SearchResult(String answer, List<ChunkHit> sources, int chunksUsed) {}
}
