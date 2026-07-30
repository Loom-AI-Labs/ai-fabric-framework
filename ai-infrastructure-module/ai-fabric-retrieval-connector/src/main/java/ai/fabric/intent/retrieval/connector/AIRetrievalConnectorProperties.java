package ai.fabric.intent.retrieval.connector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

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

    /**
     * Mandatory policy applied to every external retrieval response before it
     * can become RAG context or client-visible evidence.
     */
    private ResponsePolicyProperties responsePolicy =
        new ResponsePolicyProperties();

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

    @Data
    public static class ResponsePolicyProperties {

        private int maxDocuments = 50;
        private int maxResponseCharacters = 1_000_000;
        private int maxDocumentIdCharacters = 512;
        private int maxContentCharacters = 32_000;
        private int maxContextCharacters = 128_000;
        private int maxSourceCharacters = 256;
        private int maxUrlCharacters = 2_048;
        private int maxVectorSpaceCharacters = 128;
        private int maxMetadataEntries = 32;
        private int maxMetadataDepth = 4;
        private int maxMetadataCharacters = 8_192;
        private int maxMessageCharacters = 512;
        private int maxErrorCodeCharacters = 64;
        private Set<String> allowedUrlSchemes =
            new LinkedHashSet<>(Set.of("https"));
        private Set<String> allowedUrlHostSuffixes = new LinkedHashSet<>();
        private Set<String> allowedMetadataKeys = new LinkedHashSet<>();
        private RetrievalUnknownMetadataPolicy unknownMetadataPolicy =
            RetrievalUnknownMetadataPolicy.DROP;
    }
}
