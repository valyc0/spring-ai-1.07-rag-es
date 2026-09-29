package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.stereotype.Service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG completo: embed della domanda -> retrieval filtrato sui metadati -> soglia -> contesto -> LLM.
 * <p>
 * Retrieval in due modalita' ({@link SearchMode}):
 * <ul>
 *   <li>SEMANTIC: solo kNN sul vettore;</li>
 *   <li>HYBRID: kNN + BM25 su content, due richieste separate fuse con RRF qui nell'app,
 *       perche' RRF e retriever linear di ES richiedono una licenza superiore alla basic.</li>
 * </ul>
 * In entrambe i filtri stanno DENTRO le query (knn.filter e bool.filter), non in post_filter:
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

    private final EmbeddingModel embeddingModel;
    private final ElasticsearchOperations operations;
    private final ChatClient chatClient;
    private final int topK;
    private final int numCandidates;
    private final float minScore;
    private final String noAnswer;
    private final int rankWindow;
    private final int rrfK;
    private final int groupWindow;
    private final int maxGroups;
    private final int chunksPerGroup;

    public SearchService(EmbeddingModel embeddingModel,
                         ElasticsearchOperations operations,
                         ChatClient.Builder chatClientBuilder,
                         @Value("${app.search.top-k:5}") int topK,
                         @Value("${app.search.num-candidates:50}") int numCandidates,
                         @Value("${app.search.min-score:0.76}") float minScore,
                         @Value("${app.search.no-answer:Non ho informazioni a riguardo.}") String noAnswer,
                         @Value("${app.search.hybrid.rank-window:20}") int rankWindow,
                         @Value("${app.search.hybrid.rrf-k:60}") int rrfK,
                         @Value("${app.search.grouped.group-window:50}") int groupWindow,
                         @Value("${app.search.grouped.max-groups:10}") int maxGroups,
                         @Value("${app.search.grouped.chunks-per-group:2}") int chunksPerGroup) {
        if (rankWindow < topK) {
            throw new IllegalArgumentException(
                    "app.search.hybrid.rank-window (" + rankWindow + ") deve essere >= app.search.top-k (" + topK + ")");
        }
        // in HYBRID il kNN chiede rank-window vicini, quindi num-candidates deve coprire anche quello
        if (numCandidates < rankWindow) {
            throw new IllegalArgumentException("app.search.num-candidates (" + numCandidates
                    + ") deve essere >= app.search.hybrid.rank-window (" + rankWindow + ")");
        }
        if (groupWindow < 1) {
            throw new IllegalArgumentException("app.search.grouped.group-window (" + groupWindow + ") deve essere >= 1");
        }
        // il kNN in /search/grouped chiede group-window vicini, che non possono superare num-candidates
        if (groupWindow > numCandidates) {
            throw new IllegalArgumentException("app.search.grouped.group-window (" + groupWindow
                    + ") deve essere <= app.search.num-candidates (" + numCandidates + ")");
        }
        if (maxGroups < 1) {
            throw new IllegalArgumentException("app.search.grouped.max-groups (" + maxGroups + ") deve essere >= 1");
        }
        if (chunksPerGroup < 1) {
            throw new IllegalArgumentException(
                    "app.search.grouped.chunks-per-group (" + chunksPerGroup + ") deve essere >= 1");
        }
        this.embeddingModel = embeddingModel;
        this.operations = operations;
        this.chatClient = chatClientBuilder.build();
        this.topK = topK;
        this.numCandidates = numCandidates;
        this.minScore = minScore;
        this.noAnswer = noAnswer;
        this.rankWindow = rankWindow;
        this.rrfK = rrfK;
        this.groupWindow = groupWindow;
        this.maxGroups = maxGroups;
        this.chunksPerGroup = chunksPerGroup;
    }

    public SearchResult search(String question, SearchMode mode, SearchFilters filters) {
        Retrieval retrieval = retrieve(question, mode, filters, topK);

        // soglia di rilevanza, sempre sullo score kNN (0-1) anche in HYBRID: lo score RRF
        // dipende solo dalle posizioni, non dice se il primo chunk parla davvero della domanda.
        // Sotto soglia la risposta e' fissa e NON passa dal LLM: deterministica e senza costo.
        if (retrieval.knnTop() < minScore) {
            log.info("nessun chunk sopra la soglia: mode={} top={} minScore={} filters={} -> {}",
                    mode, retrieval.knnTop(), minScore, filters, question);
            return new SearchResult(noAnswer, mode, List.of(), 0);
        }

        List<ChunkHit> ranked = retrieval.ranked();
        String answer = answerFor(ranked, question);
        return new SearchResult(answer, mode, ranked, ranked.size());
    }

    /** Contesto "[source#index] testo" dei chunk dati -> una risposta LLM. */
    private String answerFor(List<ChunkHit> chunks, String question) {
        StringBuilder context = new StringBuilder();
        for (ChunkHit hit : chunks) {
            context.append("[").append(hit.source()).append("#")
                    .append(hit.chunkIndex()).append("] ")
                    .append(hit.content()).append("\n\n");
        }
        return chatClient.prompt()
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
    }

    // Solo knn, senza query: con una query (es. match_all) ES SOMMA i due score e la soglia
    // non sarebbe piu' il coseno. Il refresh lo fa IngestService dopo il save.
    private List<SearchHit<ChunkDocument>> knn(float[] queryVector, List<Query> filterQueries, int k) {
        NativeQuery query = NativeQuery.builder()
                .withKnnSearches(s -> {
                    s.field("embedding")
                            .queryVector(toFloatList(queryVector))
                            .k(k)
                            .numCandidates(numCandidates);
                    if (!filterQueries.isEmpty()) {
                        s.filter(filterQueries);
                    }
                    return s;
                })
                .withMaxResults(k)
                .build();
        return operations.search(query, ChunkDocument.class).getSearchHits();
    }

    // BM25 su content, con gli stessi filtri in bool.filter (non influiscono sullo score)
    private List<SearchHit<ChunkDocument>> bm25(String question, List<Query> filterQueries, int maxResults) {
        Query match = Query.of(q -> q.bool(b -> b
                .must(m -> m.match(t -> t.field("content").query(question)))
                .filter(filterQueries)));
        NativeQuery query = NativeQuery.builder()
                .withQuery(match)
                .withMaxResults(maxResults)
                .build();
        return operations.search(query, ChunkDocument.class).getSearchHits();
    }

    /** Hit kNN del miglior vicino (0 se vuoto) + lista ordinata (kNN in SEMANTIC, RRF in HYBRID). */
    private record Retrieval(float knnTop, List<ChunkHit> ranked) {}

    /**
     * Pipeline di retrieval condivisa: embed -> kNN filtrato (+ BM25 fuso con RRF in HYBRID).
     * In HYBRID ogni lista usa {limit} risultati (almeno rank-window), poi la fusione taglia a {limit}.
     */
    private Retrieval retrieve(String question, SearchMode mode, SearchFilters filters, int limit) {
        List<Query> filterQueries = filters.toQueries();
        float[] queryVector = embeddingModel.embed(question);
        int pool = mode == SearchMode.HYBRID ? Math.max(rankWindow, limit) : limit;
        List<SearchHit<ChunkDocument>> knnHits = knn(queryVector, filterQueries, pool);
        // la lista kNN e' ordinata per score decrescente: il primo e' sempre il kNN piu' alto
        float knnTop = knnHits.isEmpty() ? 0f : knnHits.get(0).getScore();
        List<ChunkHit> ranked = mode == SearchMode.HYBRID
                ? fuse(knnHits, bm25(question, filterQueries, pool), limit)
                : knnHits.stream().map(h -> ChunkHit.of(h.getContent(), h.getScore(), h.getScore(), null)).toList();
        return new Retrieval(knnTop, ranked);
    }

    /**
     * Reciprocal Rank Fusion: score(d) = somma su ogni lista di 1 / (rrfK + rank), rank da 1.
     * Usa solo le posizioni, quindi non serve normalizzare score con scale diverse
     * (kNN in 0-1, BM25 illimitato). Un chunk presente in una sola lista prende solo quel termine.
     */
    private List<ChunkHit> fuse(List<SearchHit<ChunkDocument>> knnHits,
                                List<SearchHit<ChunkDocument>> bm25Hits, int limit) {
        Map<String, float[]> scores = new LinkedHashMap<>(); // id -> {rrf, knnScore, bm25Score}
        Map<String, ChunkDocument> docs = new LinkedHashMap<>();
        accumulate(knnHits, 1, scores, docs);
        accumulate(bm25Hits, 2, scores, docs);

        return scores.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, float[]> e) -> e.getValue()[0]).reversed())
                .limit(limit)
                .map(e -> {
                    float[] v = e.getValue();
                    return ChunkHit.of(docs.get(e.getKey()), v[0],
                            Float.isNaN(v[1]) ? null : v[1], Float.isNaN(v[2]) ? null : v[2]);
                })
                .toList();
    }

    private void accumulate(List<SearchHit<ChunkDocument>> hits, int slot,
                            Map<String, float[]> scores, Map<String, ChunkDocument> docs) {
        for (int rank = 1; rank <= hits.size(); rank++) {
            SearchHit<ChunkDocument> hit = hits.get(rank - 1);
            float[] v = scores.computeIfAbsent(hit.getId(), id -> new float[]{0f, Float.NaN, Float.NaN});
            v[0] += 1f / (rrfK + rank);
            v[slot] = hit.getScore();
            docs.putIfAbsent(hit.getId(), hit.getContent());
        }
    }

    /**
     * Raggruppa per contentId tenendo i migliori {chunksPerGroup} chunk di ogni documento.
     * Presuppone {ranked} ordinato per score decrescente: il primo chunk di ogni gruppo e' il
     * suo top, e l'ordine di prima comparsa e' l'ordine dei gruppi per score. answer resta null.
     */
    static List<GroupHit> group(List<ChunkHit> ranked, int chunksPerGroup, int maxGroups) {
        Map<String, List<ChunkHit>> byContent = new LinkedHashMap<>();
        for (ChunkHit hit : ranked) {
            List<ChunkHit> chunks = byContent.computeIfAbsent(hit.contentId(), k -> new ArrayList<>());
            if (chunks.size() < chunksPerGroup) {
                chunks.add(hit);
            }
        }
        return byContent.values().stream()
                .limit(maxGroups)
                .map(chunks -> toGroup(chunks, null))
                .toList();
    }

    private static GroupHit toGroup(List<ChunkHit> chunks, String answer) {
        ChunkHit top = chunks.get(0);
        return new GroupHit(top.contentId(), top.source(), top.langId(), top.topics(),
                top.filename(), top.score(), chunks, answer);
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    /**
     * score = score usato per l'ordinamento (kNN in SEMANTIC, RRF in HYBRID);
     * knnScore / bm25Score = score originali nelle due liste, null se il chunk non c'era.
     */
    public record ChunkHit(String source, int chunkIndex, float score, Float knnScore, Float bm25Score,
                           String content, String langId, String contentId, List<String> topics,
                           String filename) {

        static ChunkHit of(ChunkDocument doc, float score, Float knnScore, Float bm25Score) {
            return new ChunkHit(doc.getSource(), doc.getChunkIndex(), score, knnScore, bm25Score,
                    doc.getContent(), doc.getLangId(), doc.getContentId(), doc.getTopics(), doc.getFilename());
        }
    }

    /** Un contentId: il suo miglior chunk (score = top del gruppo) + i chunk di contesto e la risposta. */
    public record GroupHit(String contentId, String source, String langId, List<String> topics,
                           String filename, float score, List<ChunkHit> chunks, String answer) {}

    public record SearchResult(String answer, SearchMode mode, List<ChunkHit> sources, int chunksUsed) {}
}
