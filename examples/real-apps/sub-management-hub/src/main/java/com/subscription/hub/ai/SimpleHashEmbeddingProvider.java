package com.subscription.hub.ai;

import com.ai.infrastructure.dto.AIEmbeddingRequest;
import com.ai.infrastructure.dto.AIEmbeddingResponse;
import com.ai.infrastructure.embedding.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight, fully offline embedding provider for demos.
 *
 * <p>This is NOT a semantic-quality embedding model. It exists to prove wiring:
 * indexing, migration, and vector search flows without external services or large model files.</p>
 */
@Slf4j
@Component
public class SimpleHashEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSIONS = 384;

    @Override
    public String getProviderName() {
        return "simple";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        long start = System.currentTimeMillis();
        String text = request != null ? request.getText() : "";
        List<Double> embedding = embed(text);
        return AIEmbeddingResponse.builder()
            .embedding(embedding)
            .dimensions(DIMENSIONS)
            .model("simple-hash")
            .processingTimeMs(System.currentTimeMillis() - start)
            .requestId("emb-" + UUID.randomUUID())
            .build();
    }

    @Override
    public List<AIEmbeddingResponse> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<AIEmbeddingResponse> responses = new ArrayList<>(texts.size());
        for (String text : texts) {
            responses.add(generateEmbedding(AIEmbeddingRequest.builder().text(text).build()));
        }
        return responses;
    }

    @Override
    public int getEmbeddingDimension() {
        return DIMENSIONS;
    }

    @Override
    public Map<String, Object> getStatus() {
        return Map.of(
            "provider", getProviderName(),
            "available", true,
            "dimensions", DIMENSIONS,
            "timestamp", Instant.now().toString()
        );
    }

    private List<Double> embed(String text) {
        double[] vector = new double[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return toList(vector);
        }

        String[] tokens = text.toLowerCase().split("[^a-z0-9]+");
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            applyToken(vector, token);
        }
        normalize(vector);
        return toList(vector);
    }

    private void applyToken(double[] vector, String token) {
        byte[] digest = sha256(token);
        int idx1 = ((digest[0] & 0xFF) << 8 | (digest[1] & 0xFF)) % DIMENSIONS;
        int idx2 = ((digest[2] & 0xFF) << 8 | (digest[3] & 0xFF)) % DIMENSIONS;
        int sign1 = (digest[4] & 1) == 0 ? 1 : -1;
        int sign2 = (digest[5] & 1) == 0 ? 1 : -1;

        double weight = Math.min(3.0d, 1.0d + (token.length() / 8.0d));
        vector[idx1] += sign1 * weight;
        vector[idx2] += sign2 * (weight * 0.8d);
    }

    private void normalize(double[] vector) {
        double sumSquares = 0.0d;
        for (double v : vector) {
            sumSquares += v * v;
        }
        if (sumSquares <= 0.0d) {
            return;
        }
        double invNorm = 1.0d / Math.sqrt(sumSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] * invNorm;
        }
    }

    private List<Double> toList(double[] vector) {
        List<Double> list = new ArrayList<>(vector.length);
        for (double v : vector) {
            list.add(v);
        }
        return list;
    }

    private byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("SHA-256 unavailable; falling back to Base64 hash", e);
            return Base64.getEncoder().encode(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
