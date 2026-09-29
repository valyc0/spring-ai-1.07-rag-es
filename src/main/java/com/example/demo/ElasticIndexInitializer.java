package com.example.demo;

import org.springframework.boot.CommandLineRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

@Component
public class ElasticIndexInitializer implements CommandLineRunner {

    private final ElasticsearchOperations operations;

    public ElasticIndexInitializer(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @Override
    public void run(String... args) {
        IndexOperations indexOps = operations.indexOps(ChunkDocument.class);
        if (!indexOps.exists()) {
            indexOps.createWithMapping();
        } else {
            // indice gia' esistente: aggiunge al mapping i campi nuovi di ChunkDocument
            // (es. i metadati). ES accetta solo aggiunte: cambiare il tipo di un campo
            // esistente fallisce, e li' serve drop + reindex (scripts/clear-index.sh --drop).
            indexOps.putMapping();
        }
    }
}
