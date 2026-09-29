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

    private static void addTerm(List<Query> queries, String field, String value) {
        if (value != null && !value.isBlank()) {
            queries.add(Query.of(q -> q.term(t -> t.field(field).value(value))));
        }
    }
}
