package com.example.demo;

/**
 * SEMANTIC = solo kNN sul vettore; HYBRID = kNN + BM25 su content, fusi con RRF;
 * LEXICAL = solo BM25 su content, senza embedding della domanda.
 */
public enum SearchMode {
    SEMANTIC,
    HYBRID,
    /** Solo BM25: un chunk per contentId, il piu' alto per score. Non chiama il modello di embedding. */
    LEXICAL
}
