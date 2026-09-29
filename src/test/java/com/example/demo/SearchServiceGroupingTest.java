package com.example.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceGroupingTest {

    private static SearchService.ChunkHit hit(String contentId, String source, int chunkIndex, float score) {
        return new SearchService.ChunkHit(source, chunkIndex, score, score, null,
                "content-" + source + "-" + chunkIndex, "it", contentId, List.of("t"), source + ".txt");
    }

    @Test
    void tieneUnSoloChunkPerContentId_eIlPiuAlto() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-100", "a", 0, 0.9f),
                hit("C-100", "a", 1, 0.8f),
                hit("C-200", "b", 0, 0.7f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 10);

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

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 10);

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

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 2, 10);

        assertThat(groups.get(0).chunks()).extracting(SearchService.ChunkHit::chunkIndex)
                .containsExactly(0, 1);
    }

    @Test
    void limitaIlNumeroDiGruppi() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit("C-1", "a", 0, 0.9f),
                hit("C-2", "b", 0, 0.8f),
                hit("C-3", "c", 0, 0.7f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 1, 2);

        assertThat(groups).hasSize(2);
    }

    @Test
    void gestisceContentIdNulloComeGruppo() {
        List<SearchService.ChunkHit> ranked = List.of(
                hit(null, "a", 0, 0.9f),
                hit(null, "a", 1, 0.8f));

        List<SearchService.GroupHit> groups = SearchService.group(ranked, 2, 10);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).contentId()).isNull();
        assertThat(groups.get(0).chunks()).hasSize(2);
    }
}
