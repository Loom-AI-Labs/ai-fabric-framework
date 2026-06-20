package com.ai.fabric.realapps.privacyfirst.ai;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.embedding.EmbeddingProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PrivacySimpleEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSIONS = 384;

    @Override
    public String getProviderName() {
        return "privacy-simple";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public AIEmbeddingResponse generateEmbedding(AIEmbeddingRequest request) {
        String text = request != null ? request.getText() : "";
        return AIEmbeddingResponse.builder()
            .embedding(embed(text))
            .dimensions(DIMENSIONS)
            .model(getProviderName())
            .processingTimeMs(0L)
            .requestId("privacy-emb-" + UUID.randomUUID())
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
        for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                applyToken(vector, token);
            }
        }
        normalize(vector);
        return toList(vector);
    }

    private void applyToken(double[] vector, String token) {
        byte[] digest = sha256(token);
        int idx1 = (((digest[0] & 0xFF) << 8) | (digest[1] & 0xFF)) % DIMENSIONS;
        int idx2 = (((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF)) % DIMENSIONS;
        int sign1 = (digest[4] & 1) == 0 ? 1 : -1;
        int sign2 = (digest[5] & 1) == 0 ? 1 : -1;
        double weight = Math.min(3.0d, 1.0d + token.length() / 8.0d);
        vector[idx1] += sign1 * weight;
        vector[idx2] += sign2 * weight * 0.8d;
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private void normalize(double[] vector) {
        double sumSquares = 0.0d;
        for (double value : vector) {
            sumSquares += value * value;
        }
        if (sumSquares == 0.0d) {
            return;
        }
        double invNorm = 1.0d / Math.sqrt(sumSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] * invNorm;
        }
    }

    private List<Double> toList(double[] vector) {
        List<Double> values = new ArrayList<>(vector.length);
        for (double value : vector) {
            values.add(value);
        }
        return values;
    }
}
