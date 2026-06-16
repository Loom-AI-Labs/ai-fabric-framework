package com.ai.fabric.examples.smoke;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Offline, deterministic {@link EmbeddingProvider} used by the "smoke" profile.
 *
 * <p>Produces a fixed-dimension, L2-normalized vector seeded from the text hash. It needs no ONNX model
 * file, no network, and no API key, so example apps can BOOT (and exercise vector storage/search end to
 * end) under this profile. Embeddings are stable for a given input but are NOT semantically meaningful.</p>
 *
 * <p>Selected via {@code ai.providers.embedding-provider: smoke} (see {@code application-smoke.yml}).</p>
 */
public class SmokeEmbeddingProvider implements EmbeddingProvider {

    static final String NAME = "smoke";
    static final int DIMENSION = 384;

    @Override
    public String getProviderName() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        return deterministicEmbedding(request != null ? request.getText() : "", NAME);
    }

    @Override
    public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts) {
        List<AIEmbeddingResponse> responses = new ArrayList<>();
        if (texts != null) {
            for (String text : texts) {
                responses.add(deterministicEmbedding(text, NAME));
            }
        }
        return responses;
    }

    @Override
    public int getEmbeddingDimension() {
        return DIMENSION;
    }

    @Override
    public Map<String, Object> getStatus() {
        return Map.of(
            "provider", NAME,
            "available", true,
            "dimension", DIMENSION,
            "details", "offline deterministic stub (smoke profile)"
        );
    }

    /** Build a deterministic, L2-normalized embedding seeded from the text. */
    static AIEmbeddingResponse deterministicEmbedding(String text, String model) {
        long seed = (text == null ? "" : text).hashCode() & 0xffffffffL;
        Random random = new Random(seed);
        List<Double> vector = new ArrayList<>(DIMENSION);
        double sumSquares = 0.0;
        for (int i = 0; i < DIMENSION; i++) {
            double value = random.nextDouble() * 2.0 - 1.0;
            vector.add(value);
            sumSquares += value * value;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm > 0.0) {
            for (int i = 0; i < DIMENSION; i++) {
                vector.set(i, vector.get(i) / norm);
            }
        }
        return AIEmbeddingResponse.builder()
            .embedding(vector)
            .model(model)
            .dimensions(DIMENSION)
            .processingTimeMs(0L)
            .requestId("smoke-" + UUID.randomUUID())
            .build();
    }
}
