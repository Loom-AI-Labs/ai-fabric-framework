package ai.fabric.intent.action.connector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for executing connector-backed actions via the Customer Connector API.
 *
 * <p>Greenfield: when connector actions are enabled, configuration should be explicit and fail-fast.
 * Missing required configuration (e.g., base URL) must terminate startup if connector action sources are present.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.actions.connector")
public class AIActionConnectorProperties {

    /**
     * Base URL of the Customer Connector API, e.g. {@code https://relay.customer.example}.
     */
    private String baseUrl;

    /**
     * Connector execute endpoint path (relative to {@link #baseUrl}).
     */
    private String executePath = "/actions/execute";

    /**
     * Connect timeout for connector calls.
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * Read timeout for connector calls.
     */
    private Duration readTimeout = Duration.ofSeconds(15);

    /**
     * Maximum retry attempts for connector calls (including the first attempt).
     */
    private int maxAttempts = 3;

    /**
     * Initial backoff delay for retries.
     */
    private Duration initialBackoff = Duration.ofSeconds(1);

    /**
     * Static API key header configuration (optional).
     */
    private ApiKeyProperties apiKey = new ApiKeyProperties();

    /**
     * Dedicated connector admin API key configuration used for read-only admin proxy surfaces.
     */
    private AdminApiKeyProperties admin = new AdminApiKeyProperties();

    /**
     * HMAC signing configuration (optional, recommended for production).
     */
    private HmacProperties hmac = new HmacProperties();

    /**
     * Generic MCP execution gateway used for adapterType=mcp-tool actions.
     */
    private McpGatewayProperties mcpGateway = new McpGatewayProperties();

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
    public static class AdminApiKeyProperties {
        /**
         * Header name to send when {@link #value} is configured.
         */
        private String header = "X-ADMIN-API-KEY";

        /**
         * Admin API key value. When blank/null, runtime admin proxy falls back to {@link ApiKeyProperties}.
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
    public static class McpGatewayProperties {
        /**
         * Internal base URL of the managed MCP execution gateway.
         */
        private String baseUrl;

        /**
         * Action execution endpoint path relative to {@link #baseUrl}.
         */
        private String executePath = "/api/internal/mcp/actions/execute";

        /**
         * Header name used by the gateway internal API key.
         */
        private String apiKeyHeader = "X-MCP-GATEWAY-API-KEY";

        /**
         * Internal API key value. Required when adapterType=mcp-tool actions execute.
         */
        private String apiKey;
    }
}
