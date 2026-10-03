package com.example.demo;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * Filtri sui metadati per /search. Ogni campo e' opzionale: null (o lista vuota) = nessun
 * filtro. Tra campi diversi vale AND; dentro topics vale OR (basta uno dei topic).
 */
public record SearchFilters(String source,
                            String langId,
                            String contentId,
                            List<String> topics,
                            String filename) {

    /** Una term/terms query per ogni filtro valorizzato, da mettere in filter (niente score). */
    public List<Query> toQueries() {
        List<Query> queries = new ArrayList<>();
        addTerm(queries, "source", source);
        addTerm(queries, "langId", langId);
        addTerm(queries, "contentId", contentId);
        addTerm(queries, "filename", filename);
        if (topics != null && !topics.isEmpty()) {
            List<FieldValue> values = topics.stream().map(FieldValue::of).toList();
            queries.add(Query.of(q -> q.terms(t -> t.field("topics").terms(v -> v.value(values)))));
        }
        return queries;
    }

    /**
     * Filtri con i valori di questo dove sono valorizzati, quelli di other dove questo manca.
     * <p>
     * Serve a /agent/chat: i filtri arrivano da due lati, la richiesta HTTP e la scelta del
     * modello, e il modello non puo' scavalcare chi ha scritto la richiesta. Campo per campo e
     * non AND: {@code contentId} e' un valore singolo, quindi i due non possono coesistere e la
     * precedenza va detta, non lasciata all'ordine dei parametri. topics tiene la lista intera
     * per la stessa ragione (e non la unione delle due).
     */
    public SearchFilters merge(SearchFilters other) {
        if (other == null) {
            return this;
        }
        return new SearchFilters(
                firstValued(source, other.source),
                firstValued(langId, other.langId),
                firstValued(contentId, other.contentId),
                topics != null && !topics.isEmpty() ? topics : other.topics,
                firstValued(filename, other.filename));
    }

    private static String firstValued(String questo, String altro) {
        return questo != null && !questo.isBlank() ? questo : altro;
    }

    private static void addTerm(List<Query> queries, String field, String value) {
        if (value != null && !value.isBlank()) {
            queries.add(Query.of(q -> q.term(t -> t.field(field).value(value))));
        }
    }
}
