package com.example.demo;

import java.util.List;

/**
 * Body JSON di POST /ingest: il testo da indicizzare piu' i metadati, che vengono copiati
 * uguali su ogni chunk del documento. Solo source e text sono obbligatori.
 */
public record IngestRequest(String source,
                            String text,
                            String langId,
                            String contentId,
                            List<String> topics,
                            String filename) {
}
