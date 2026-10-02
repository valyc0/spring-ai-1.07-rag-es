package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Retrieval: embed della domanda -> kNN (eventualmente + BM25) filtrato sui metadati -> soglia.
 * <p>
 * Non conosce l'LLM: restituisce solo i chunk. Lo usano sia {@link SearchService} (che li
 * infila direttamente nel prompt, RAG classico) sia {@link SearchTools} (che li restituisce al
 * modello come risultato di una chiamata a tool, RAG agentico). Averlo qui evita di duplicare
 * la logica di retrieval nei due percorsi.
 * <p>
 * Due modalita' ({@link SearchMode}):
 * <ul>
 *   <li>SEMANTIC: solo kNN sul vettore;</li>
 *   <li>HYBRID: kNN + BM25 su content, due richieste separate fuse con RRF qui nell'app,
 *       perche' RRF e retriever linear di ES richiedono una licenza superiore alla basic.</li>
 * </ul>
 * In entrambe i filtri stanno DENTRO le query (knn.filter e bool.filter), non in post_filter:
 * cosi' ES cerca i vicini solo tra i chunk che passano i filtri e restituisce comunque k risultati.
 */
@Service
public class ChunkRetriever {

    private static final Logger log = LoggerFactory.getLogger(ChunkRetriever.class);

    private final EmbeddingModel embeddingModel;
    private final ElasticsearchOperations operations;
    private final int topK;
    private final int numCandidates;
    private final float minScore;
    private final int rankWindow;
    private final int rrfK;

    public ChunkRetriever(EmbeddingModel embeddingModel,
                          ElasticsearchOperations operations,
                          @Value("${app.search.top-k:5}") int topK,
                          @Value("${app.search.num-candidates:50}") int numCandidates,
                          @Value("${app.search.min-score:0.76}") float minScore,
                          @Value("${app.search.hybrid.rank-window:20}") int rankWindow,
                          @Value("${app.search.hybrid.rrf-k:60}") int rrfK) {
        if (rankWindow < topK) {
            throw new IllegalArgumentException(
                    "app.search.hybrid.rank-window (" + rankWindow + ") deve essere >= app.search.top-k (" + topK + ")");
        }
        // in HYBRID il kNN chiede rank-window vicini, quindi num-candidates deve coprire anche quello
        if (numCandidates < rankWindow) {
            throw new IllegalArgumentException("app.search.num-candidates (" + numCandidates
                    + ") deve essere >= app.search.hybrid.rank-window (" + rankWindow + ")");
        }
        this.embeddingModel = embeddingModel;
        this.operations = operations;
        this.topK = topK;
        this.numCandidates = numCandidates;
        this.minScore = minScore;
        this.rankWindow = rankWindow;
        this.rrfK = rrfK;
    }

    /**
     * Un giro di retrieval completo.
     *
     * @param ranked   chunk ordinati per punteggio (RRF in HYBRID, kNN in SEMANTIC), vuoto se
     *                 sotto soglia.
     * @param topScore score kNN del chunk piu' vicino, 0 se non c'e' niente. Distingue "i filtri
     *                 non hanno lasciato passare nulla" da "il chunk migliore era troppo debole".
     * @param belowThreshold true se ranked e' vuoto solo perche' la soglia non e' stata superata.
     */
    public record Retrieval(List<ChunkHit> ranked, float topScore, boolean belowThreshold) {

        public boolean empty() {
            return ranked.isEmpty();
        }
    }

    public Retrieval retrieve(String question, SearchMode mode, SearchFilters filters) {
        List<Query> filterQueries = filters.toQueries();

        // 1. embed della domanda (stesso modello usato in ingest, altrimenti i vettori non confrontabili)
        float[] queryVector = embeddingModel.embed(question);

        // 2. kNN filtrato. In HYBRID chiede rank-window vicini invece di top-k: RRF lavora
        //    sui ranking, e un chunk al 10o posto in kNN ma 1o in BM25 deve poter risalire.
        List<SearchHit<ChunkDocument>> knnHits =
                knn(queryVector, filterQueries, mode == SearchMode.HYBRID ? rankWindow : topK);

        // 3. soglia di rilevanza, sempre sullo score kNN (0-1) anche in HYBRID: lo score RRF
        //    dipende solo dalle posizioni, non dice se il primo chunk parla davvero della domanda.
        //    Con filtri troppo stretti knnHits e' vuoto e si finisce qui.
        float top = knnHits.isEmpty() ? 0f : knnHits.get(0).getScore();
        if (knnHits.isEmpty() || top < minScore) {
            log.info("nessun chunk sopra la soglia: mode={} top={} minScore={} filters={} -> {}",
                    mode, top, minScore, filters, question);
            return new Retrieval(List.of(), top, !knnHits.isEmpty());
        }

        List<ChunkHit> ranked = mode == SearchMode.HYBRID
                ? fuse(knnHits, bm25(question, filterQueries))
                : knnHits.stream().map(h -> ChunkHit.of(h.getContent(), h.getScore(), h.getScore(), null)).toList();

        return new Retrieval(ranked, top, false);
    }

    public float minScore() {
        return minScore;
    }

    public int topK() {
        return topK;
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
    private List<SearchHit<ChunkDocument>> bm25(String question, List<Query> filterQueries) {
        Query match = Query.of(q -> q.bool(b -> b
                .must(m -> m.match(t -> t.field("content").query(question)))
                .filter(filterQueries)));
        NativeQuery query = NativeQuery.builder()
                .withQuery(match)
                .withMaxResults(rankWindow)
                .build();
        return operations.search(query, ChunkDocument.class).getSearchHits();
    }

    /**
     * Reciprocal Rank Fusion: score(d) = somma su ogni lista di 1 / (rrfK + rank), rank da 1.
     * Usa solo le posizioni, quindi non serve normalizzare score con scale diverse
     * (kNN in 0-1, BM25 illimitato). Un chunk presente in una sola lista prende solo quel termine.
     */
    private List<ChunkHit> fuse(List<SearchHit<ChunkDocument>> knnHits, List<SearchHit<ChunkDocument>> bm25Hits) {
        Map<String, float[]> scores = new LinkedHashMap<>(); // id -> {rrf, knnScore, bm25Score}
        Map<String, ChunkDocument> docs = new LinkedHashMap<>();
        accumulate(knnHits, 1, scores, docs);
        accumulate(bm25Hits, 2, scores, docs);

        return scores.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, float[]> e) -> e.getValue()[0]).reversed())
                .limit(topK)
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

        /** Riferimento compatto: usato per le citazioni e come chiave di deduplica fra tool-call. */
        public String citation() {
            return source + "#" + chunkIndex;
        }
    }
}