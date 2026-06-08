package com.ai.infrastructure.intent.retrieval.connector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for documents-only external retrieval via the Customer Connector API.
 *
 * <p>When enabled ({@code ai.retrieval.connector.enabled=true}), the runtime calls the customer
 * endpoint {@code POST /retrieval/search} to retrieve documents/chunks.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.retrieval.connector")
public class AIRetrievalConnectorProperties {

    /**
     * Whether the external retrieval connector is enabled.
     */
    private boolean enabled = false;

    /**
     * Base URL of the Customer Connector API, e.g. {@code https://relay.customer.example}.
     */
    private String baseUrl;

    /**
     * Retrieval search endpoint path (relative to {@link #baseUrl}).
     */
    private String searchPath = "/retrieval/search";

    /**
     * Connect timeout for retrieval connector calls.
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * Read timeout for retrieval connector calls.
     */
    private Duration readTimeout = Duration.ofSeconds(15);

    /**
     * Maximum retry attempts for retrieval connector calls (including the first attempt).
     */
    private int maxAttempts = 3;

    /**
     * Initial backoff delay for retries.
     */
    private Duration initialBackoff = Duration.ofSeconds(1);

    /**
     * Maximum {@code topK} value to send to the connector (defense-in-depth).
     */
    private int maxTopK = 50;

    /**
     * Static API key header configuration (optional).
     */
    private ApiKeyProperties apiKey = new ApiKeyProperties();

    /**
     * HMAC signing configuration (optional, recommended for production).
     */
    private HmacProperties hmac = new HmacProperties();

    @Data
    public static class ApiKeyProperties {
        /**
         * Header name to send when {@link #value} is configured.
         */
        private String header = "X-AIFABRIC-API-KEY";

        /**
         * API key value. When blank/null, the header is not sent.
         */
        private String value;
    }

    @Data
    public static class HmacProperties {
        /**
         * Shared secret for request signing. When blank/null, HMAC signing is disabled.
         */
        private String secret;

        /**
         * Timestamp header name.
         */
        private String timestampHeader = "X-AIFABRIC-TIMESTAMP";

        /**
         * Nonce header name.
         */
        private String nonceHeader = "X-AIFABRIC-NONCE";

        /**
         * Signature header name.
         */
        private String signatureHeader = "X-AIFABRIC-SIGNATURE";
    }
}

