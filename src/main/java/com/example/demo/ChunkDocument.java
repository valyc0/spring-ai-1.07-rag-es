package com.example.demo;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "#{@environment.getProperty('app.elastic.index')}", createIndex = false)
public class ChunkDocument {

    public static final int EMBEDDING_DIMS = 1024;

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String source;

    @Field(type = FieldType.Integer)
    private int chunkIndex;

    @Field(type = FieldType.Text)
    private String content;

    // deve coincidere con spring.ai.openai.embedding.options.dimensions
    @Field(type = FieldType.Dense_Vector, dims = EMBEDDING_DIMS, index = true, similarity = "cosine")
    private float[] embedding;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }
}
