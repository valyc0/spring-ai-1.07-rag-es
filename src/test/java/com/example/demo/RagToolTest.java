package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Funzioni pure di RagTool: formattazione del contesto, riassunto dei filtri, parsing della
 * modalita'. Il contratto del tool stesso (filtri, soglia, trace) si verifica con SearchService
 * finto; il ciclo agentico vero si verifica con curl, che chiama l'LLM.
 */
class RagToolTest {

    private static SearchService.ChunkHit hit(String contentId, String source, int chunkIndex, float score) {
        return new SearchService.ChunkHit(source, chunkIndex, score, score, null,
                "testo di " + source + " " + chunkIndex, "it", contentId, List.of("t"), source + ".txt");
    }

    @Test
    void ilContestoHaUnBloccoPerChunkNellaFormaSourceChunkIndex() {
        String context = RagTool.formatContext(
                List.of(hit("C-1", "manuale", 3, 0.9f), hit("C-2", "faq", 0, 0.8f)),
                SearchMode.SEMANTIC);

        assertThat(context).contains("[manuale#3] testo di manuale 3");
        assertThat(context).contains("[faq#0] testo di faq 0");
        assertThat(context).contains("modalita' semantic, 2 chunk trovati");
    }

    @Test
    void laRigaDelleFontiRiportaScoreEMetadatiDelDocumento() {
        String context = RagTool.formatContext(List.of(hit("C-100", "manuale", 3, 0.9123f)), SearchMode.HYBRID);

        assertThat(context).contains("Fonti: manuale#3 (score 0.912, contentId C-100, langId it)");
        assertThat(context).contains("modalita' hybrid, 1 chunk trovato");
    }

    @Test
    void leFontiNonRiportanoMetadatiAssenti() {
        SearchService.ChunkHit senzaMetadati = new SearchService.ChunkHit("s", 0, 0.5f, 0.5f, null,
                "testo", null, null, null, null);

        assertThat(RagTool.formatContext(List.of(senzaMetadati), SearchMode.LEXICAL))
                .contains("Fonti: s#0 (score 0.500)")
                .doesNotContain("null");
    }

    @Test
    void ilRiassuntoDeiFiltriElencaSoloQuelliValorizzati() {
        assertThat(RagTool.filtersSummary(new SearchFilters(null, "it", null, List.of("errori"), null)))
                .isEqualTo("langId=it, topics=[errori]");
        assertThat(RagTool.filtersSummary(new SearchFilters("doc1", null, null, null, null)))
                .isEqualTo("source=doc1");
    }

    @Test
    void senzaFiltriIlRiassuntoEUnTrattino() {
        assertThat(RagTool.filtersSummary(new SearchFilters(null, null, null, List.of(), "  ")))
                .isEqualTo("-");
    }

    @Test
    void iTopicVuotiNonDiventanoUnFiltroCheNonTrovaNulla() {
        // il modello puo' rispondere topics:[""] quando i topic non servono: una terms query con ""
        // non matcherebbe nessun chunk, quindi il filtro va rimosso, non passato cosi'
        assertThat(RagTool.cleanTopics(null)).isNull();
        assertThat(RagTool.cleanTopics(List.of())).isNull();
        assertThat(RagTool.cleanTopics(java.util.Arrays.asList("", "  ", null))).isNull();
        assertThat(RagTool.cleanTopics(java.util.Arrays.asList("errori", "", null))).containsExactly("errori");
    }

    @Test
    void laModalitaArrivaMinuscolaMaIlDefaultESemantic() {
        assertThat(RagTool.modeOrDefault("hybrid")).isEqualTo(SearchMode.HYBRID);
        assertThat(RagTool.modeOrDefault("LEXICAL")).isEqualTo(SearchMode.LEXICAL);
        assertThat(RagTool.modeOrDefault(null)).isEqualTo(SearchMode.SEMANTIC);
        assertThat(RagTool.modeOrDefault("  ")).isEqualTo(SearchMode.SEMANTIC);
    }

    @Test
    void unaModalitaInventataDalModelloDegradaInSemanticENonFallisce() {
        // il tool non puo' rispondere 400 come /search: un argomento sbagliato del modello
        // non deve far fallire la richiesta dell'utente
        assertThat(RagTool.modeOrDefault("full-text")).isEqualTo(SearchMode.SEMANTIC);
    }

    @Test
    void ilToolPassaIParametriDelModelloARicercaEAccodaLaTrace() {
        SearchService searchService = mock(SearchService.class);
        when(searchService.chunksAboveThreshold(anyString(), any(), any()))
                .thenReturn(List.of(hit("C-100", "manuale", 0, 0.9123f)));
        RagTool tool = new RagTool(searchService, "vuoto");
        List<RagTool.SearchTrace> traces = new ArrayList<>();

        String context = tool.search("errore E4521", "hybrid", null, "it", null, List.of("errori"), null,
                new ToolContext(Map.of(RagTool.TRACE_KEY, traces)));

        // la stessa soglia di /search decide: se non passa il tool non restituisce nulla di suo
        assertThat(context).contains("[manuale#0] testo di manuale 0");
        assertThat(traces).containsExactly(
                new RagTool.SearchTrace("errore E4521", "hybrid", "langId=it, topics=[errori]", 1));
    }

    @Test
    void senzaChunkIlToolRestituisceLAvvisoENonUnContesto() {
        SearchService searchService = mock(SearchService.class);
        when(searchService.chunksAboveThreshold(anyString(), any(), any())).thenReturn(List.of());
        RagTool tool = new RagTool(searchService, "Nessun chunk trovato.");
        List<RagTool.SearchTrace> traces = new ArrayList<>();

        String result = tool.search("campionato 1994", null, null, null, null, null, null,
                new ToolContext(Map.of(RagTool.TRACE_KEY, traces)));

        assertThat(result).isEqualTo("Nessun chunk trovato.");
        assertThat(traces.get(0)).isEqualTo(new RagTool.SearchTrace("campionato 1994", "semantic", "-", 0));
    }

    @Test
    void unaDomandaVuotaNonAccedeAllIndice() {
        // altrimenti l'embedding andrebbe in errore e la richiesta dell'utente finirebbe in 500
        SearchService searchService = mock(SearchService.class);

        String result = new RagTool(searchService, "Nessun chunk trovato.")
                .search("   ", null, null, null, null, null, null, null);

        assertThat(result).isEqualTo("Nessun chunk trovato.");
        verifyNoInteractions(searchService);
    }
}
