package com.example.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceGroupingTest {

    // soglia 0: i test precedenti verificano solo il raggruppamento, senza interferenza
    private static final float NO_MIN = 0f;

    private static SearchService.ChunkHit hit(String contentId, String source, int chunkIndex, float score) {
        return new SearchService.ChunkHit(source, chunkIndex, score, score, null,
                "content-" + source + "-" + chunkIndex, "it", contentId, List.of("t"), source + ".txt");
    }

    /** Chunk solo nella lista BM25: knnScore null, in HYBRID puo' succedere. */
    private static SearchService.ChunkHit bm25OnlyHit(String contentId, String source, float rrfScore) {
        return new SearchService.ChunkHit(source, 0, rrfScore, null, rrfScore,
                "content-" + source, "it", contentId, List.of("t"), source + ".txt");
    }

    @Test
    void tieneUnSoloChunkPerContentId_eIlPiuAlto() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-100", "a", 0, 0.9f),
                hit("C-100", "a", 1, 0.8f),
                hit("C-200", "b", 0, 0.7f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 10, NO_MIN);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).contentId()).isEqualTo("C-100");
        assertThat(groups.get(0).score()).isEqualTo(0.9f);
        assertThat(groups.get(0).chunks()).hasSize(1);
    }

    @Test
    void ordinaIGruppiPerScoreDecrescente() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-100", "a", 0, 0.9f),
                hit("C-200", "b", 0, 0.85f),
                hit("C-300", "c", 0, 0.5f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 10, NO_MIN);

        assertThat(groups).extracting(SearchService.GroupHit::contentId)
                .containsExactly("C-100", "C-200", "C-300");
    }

    @Test
    void tieneFinoAChunksPerGroupChunkDelDocumento() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-100", "a", 0, 0.9f),
                hit("C-100", "a", 1, 0.8f),
                hit("C-100", "a", 2, 0.7f),
                hit("C-200", "b", 0, 0.6f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 2, 10, NO_MIN);

        assertThat(groups.get(0).chunks()).extracting(SearchService.ChunkHit::chunkIndex)
                .containsExactly(0, 1);
    }

    @Test
    void limitaIlNumeroDiGruppi() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-1", "a", 0, 0.9f),
                hit("C-2", "b", 0, 0.8f),
                hit("C-3", "c", 0, 0.7f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 2, NO_MIN);

        assertThat(groups).hasSize(2);
    }

    @Test
    void ogniDocumentoPortaSoloIPropriChunkNelContesto() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-1", "a", 0, 0.9f),
                hit("C-2", "b", 0, 0.85f),
                hit("C-1", "a", 1, 0.8f),
                hit("C-2", "b", 1, 0.75f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 2, 10, NO_MIN);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).chunks()).extracting(SearchService.ChunkHit::source).containsOnly("a");
        assertThat(groups.get(1).chunks()).extracting(SearchService.ChunkHit::source).containsOnly("b");
    }

    @Test
    void scartaIlDocumentoSottoSoglia() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-100", "a", 0, 0.9f),
                hit("C-200", "b", 0, 0.5f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 10, 0.7f);

        assertThat(groups).extracting(SearchService.GroupHit::contentId).containsExactly("C-100");
    }

    @Test
    void sottoSogliaIlGruppoNonConsumaUnPostoDiMaxGroups() {
        // C-200 e' il primo in ranking RRF ma il suo knnScore e' sotto soglia: senza filtro
        // occuperebbe uno dei due posti di max-groups e C-300 verrebbe perso
        List<SearchService.ChunkHit> ranked = List.of(
                new SearchService.ChunkHit("b", 0, 0.95f, 0.4f, 9f, "c0", "it", "C-200", List.of("t"), "b.txt"),
                new SearchService.ChunkHit("a", 0, 0.8f, 0.8f, null, "c0", "it", "C-100", List.of("t"), "a.txt"),
                new SearchService.ChunkHit("c", 0, 0.75f, 0.75f, null, "c0", "it", "C-300", List.of("t"), "c.txt"));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 2, 0.7f);

        assertThat(groups).extracting(SearchService.GroupHit::contentId)
                .containsExactly("C-100", "C-300");
    }

    @Test
    void laSogliaSiApplicaAncheAiChunkDiContesto() {
        // il primo chunk e' sotto soglia: non entra nel contesto, anche se il gruppo passa
        List<SearchService.ChunkHit> ranked = List.of(
                new SearchService.ChunkHit("a", 0, 0.9f, 0.6f, null, "c0", "it", "C-100", List.of("t"), "a.txt"),
                new SearchService.ChunkHit("a", 1, 0.7f, 0.8f, null, "c1", "it", "C-100", List.of("t"), "a.txt"));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 2, 10, 0.7f);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).chunks()).extracting(SearchService.ChunkHit::chunkIndex).containsExactly(1);
        // il rappresentante e' il primo chunk SOPRA SOGLIA, non quello col knnScore piu' alto
        assertThat(groups.get(0).score()).isEqualTo(0.7f);
    }

    @Test
    void ilContestoNonContieneChunkSottoSoglia() {
        // C-100 ha un chunk sotto soglia: non entra nel contesto del gruppo, nemmeno come secondo
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-100", "a", 0, 0.9f),
                new SearchService.ChunkHit("a", 1, 0.7f, 0.65f, null, "c1", "it", "C-100", List.of("t"), "a.txt"),
                hit("C-200", "b", 0, 0.85f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 2, 10, 0.7f);

        assertThat(groups).extracting(SearchService.GroupHit::contentId).containsExactly("C-100", "C-200");
        assertThat(groups.get(0).chunks()).extracting(SearchService.ChunkHit::chunkIndex).containsExactly(0);
    }

    @Test
    void scartaIlGruppoDiSoliChunkBm25() {
        // in HYBRID un chunk solo BM25 ha knnScore null: non puo' dimostrare somiglianza
        List<SearchService.ChunkHit> ranked = List.of(
                bm25OnlyHit("C-100", "a", 0.9f),
                hit("C-200", "b", 0, 0.8f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 10, 0.7f);

        assertThat(groups).extracting(SearchService.GroupHit::contentId).containsExactly("C-200");
    }
}
