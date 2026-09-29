package com.example.demo;

/** SEMANTIC = solo kNN sul vettore; HYBRID = kNN + BM25 su content, fusi con RRF. */
public enum SearchMode {
    SEMANTIC,
    HYBRID
}
