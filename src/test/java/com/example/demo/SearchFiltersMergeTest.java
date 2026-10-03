package com.example.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchFiltersMergeTest {

    @Test
    void ilValoreDiQuestoVinceDoveEValorizzato() {
        SearchFilters forced = new SearchFilters(null, "en", "C-101", null, null);
        SearchFilters delModello = new SearchFilters("doc1", "it", "C-999", List.of("errori"), "a.pdf");

        SearchFilters merged = forced.merge(delModello);

        assertThat(merged.langId()).isEqualTo("en");
        assertThat(merged.contentId()).isEqualTo("C-101");
        // i campi che la richiesta non ha toccato restano quelli del modello
        assertThat(merged.source()).isEqualTo("doc1");
        assertThat(merged.topics()).containsExactly("errori");
        assertThat(merged.filename()).isEqualTo("a.pdf");
    }

    @Test
    void unValoreVuotoNonSovrastaQuelloDelModello() {
        SearchFilters forced = new SearchFilters(null, "  ", null, List.of(), null);

        assertThat(forced.merge(new SearchFilters(null, "it", null, List.of("cucina"), null)).langId())
                .isEqualTo("it");
    }

    @Test
    void topicsPrendeLaListaInteraNonLUnione() {
        // topics e' una OR dentro il campo: unire le due liste cambierebbe il significato del
        // filtro, quindi vale la stessa regola degli altri campi, la lista intera
        SearchFilters forced = new SearchFilters(null, null, null, List.of("errori"), null);

        assertThat(forced.merge(new SearchFilters(null, null, null, List.of("cucina"), null)).topics())
                .containsExactly("errori");
    }

    @Test
    void unireConNullOConVuotoNonCambiaNiente() {
        SearchFilters forced = new SearchFilters(null, "en", null, null, null);

        assertThat(forced.merge(null)).isEqualTo(forced);
        assertThat(forced.merge(new SearchFilters(null, null, null, null, null))).isEqualTo(forced);
    }

    @Test
    void iFiltriUnitiRestanoQueryEquivalentiAQuelliDiPartenza() {
        // la merge non deve cambiare le query generate: il tool le usa cosi' com'erano su /search
        SearchFilters merged = new SearchFilters(null, "en", null, List.of("errori"), null)
                .merge(new SearchFilters("doc1", "it", null, null, null));

        assertThat(merged.toQueries()).hasSize(3);
        assertThat(merged.toQueries().toString())
                .contains("\"term\":{\"source\":{\"value\":\"doc1\"}}")
                .contains("\"term\":{\"langId\":{\"value\":\"en\"}}")
                .contains("\"terms\":{\"topics\":[\"errori\"]}");
    }
}
