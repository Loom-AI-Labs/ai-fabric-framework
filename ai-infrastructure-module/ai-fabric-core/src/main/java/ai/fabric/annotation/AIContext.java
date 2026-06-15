package ai.fabric.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AIContext Annotation
 *
 * <p>Marks a field as structured metadata that should be included in LLM context
 * but NOT embedded for semantic search. This annotation identifies fields that provide
 * contextual information to improve LLM responses without the overhead of vector embeddings.</p>
 *
 * <p><strong>Purpose:</strong> Fields annotated with @AIContext will be:
 * <ul>
 *   <li>Extracted and stored as structured JSON metadata</li>
 *   <li>Included in LLM prompts for contextual understanding</li>
 *   <li>Stored as vector metadata in the vector database</li>
 *   <li>NOT embedded or indexed in vector database (reduces cost and complexity)</li>
 * </ul>
 * </p>
 *
 * <p><strong>Key Difference from @AISearchable:</strong><br>
 * @AISearchable → Content that gets embedded and searched (description, title, content)<br>
 * @AIContext → Structured metadata for LLM context only (category, status, tags, ID)
 * </p>
 *
 * <p><strong>When to Use @AIContext:</strong></p>
 * <ul>
 *   <li>Categorical data: status, category, type, priority</li>
 *   <li>Structured identifiers: IDs, codes, references</li>
 *   <li>Numeric metadata: counts, scores, ratings</li>
 *   <li>Timestamps: createdAt, updatedAt, publishedAt</li>
 *   <li>Enumerated values: enums, boolean flags</li>
 * </ul>
 *
 * <p><strong>Configuration Priority:</strong><br>
 * YAML configuration (ai-entity-config.yml) &gt; Annotation defaults &gt; Framework defaults
 * </p>
 *
 * <p><strong>Example Usage:</strong></p>
 * <pre>{@code
 * @Entity
 * @AICapable(entityType = "order")
 * public class Order {
 *     @Id
 *     @AIContext  // Include ID in LLM context
 *     private UUID id;
 *
 *     @AISearchable  // Embedded and searched
 *     private String customerNotes;
 *
 *     @AIContext(contextKey = "status")  // Metadata only
 *     private OrderStatus status;
 *
 *     @AIContext(contextKey = "order_total")
 *     private BigDecimal total;
 *
 *     @AIContext(format = "yyyy-MM-dd")  // Custom date format
 *     private LocalDateTime createdAt;
 * }
 * }</pre>
 *
 * <p><strong>Performance Benefits:</strong></p>
 * <ul>
 *   <li>No embedding generation cost (faster indexing)</li>
 *   <li>No vector storage (reduced memory footprint)</li>
 *   <li>Structured data preserves semantics (better than embedding categorical values)</li>
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Annotation processing is thread-safe.</p>
 *
 * @author AI Infrastructure Team
 * @version 2.0.0
 * @since 2.0.0
 * @see ai.fabric.annotation.AISearchable
 * @see ai.fabric.annotation.AICapable
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AIContext {

    /**
     * Key name for this context field in the metadata JSON.
     *
     * <p>By default, uses the Java field name. Override this to customize
     * how the field appears in LLM prompts and metadata.</p>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * @AIContext(contextKey = "customer_id")
     * private UUID customerId;
     * // Appears as "customer_id" in metadata instead of "customerId"
     * }</pre>
     *
     * @return Custom context key (default: "" = use Java field name)
     */
    String contextKey() default "";

    /**
     * Data type hint for this context field.
     *
     * <p>Helps the LLM understand how to interpret this field.
     * While the framework auto-detects types, explicit hints can improve LLM understanding.</p>
     *
     * <p><strong>Available Types:</strong></p>
     * <ul>
     *   <li>auto: Auto-detect from field type (default)</li>
     *   <li>string: Text value</li>
     *   <li>number: Numeric value</li>
     *   <li>boolean: True/false value</li>
     *   <li>date: Date/timestamp</li>
     *   <li>enum: Enumerated value</li>
     *   <li>id: Identifier/reference</li>
     *   <li>json: Complex nested object</li>
     * </ul>
     *
     * @return The data type hint (default: "auto")
     */
    String dataType() default "auto";

    /**
     * Format string for dates, numbers, or custom serialization.
     *
     * <p><strong>Date Formats:</strong> Use Java SimpleDateFormat patterns</p>
     * <pre>{@code
     * @AIContext(format = "yyyy-MM-dd")
     * private LocalDateTime createdAt;  // "2026-01-06"
     * }</pre>
     *
     * <p><strong>Number Formats:</strong> Use DecimalFormat patterns</p>
     * <pre>{@code
     * @AIContext(format = "#,##0.00")
     * private BigDecimal price;  // "1,234.50"
     * }</pre>
     *
     * @return The format string (default: "" = use default formatting)
     */
    String format() default "";

    /**
     * Whether to include this field in LLM prompts.
     *
     * <p>Set to false to store in metadata but exclude from LLM context.
     * Useful for audit fields that shouldn't influence LLM decisions.</p>
     *
     * @return true if field should be included in LLM context (default: true)
     */
    boolean includeInLLMContext() default true;

    /**
     * Whether to include this field in API responses.
     *
     * <p>Set to false for sensitive or internal metadata that should not
     * be exposed through the AI search API.</p>
     *
     * @return true if field should be included in API responses (default: true)
     */
    boolean includeInResponse() default true;

    /**
     * Description of this context field for LLM understanding.
     *
     * <p>Provides additional context to the LLM about what this field represents
     * and how it should be interpreted. This can significantly improve LLM accuracy.</p>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * @AIContext(description = "Customer tier: BRONZE, SILVER, GOLD, PLATINUM")
     * private CustomerTier tier;
     * }</pre>
     *
     * @return Description for LLM (default: "" = no description)
     */
    String description() default "";

    /**
     * Priority of this context field (1-10, higher = more important).
     *
     * <p>Influences the prominence of this field in LLM prompts.
     * Higher priority fields may be positioned earlier or highlighted.</p>
     *
     * <p><strong>Typical Values:</strong></p>
     * <ul>
     *   <li>8-10: Critical metadata (IDs, status)</li>
     *   <li>5-7: Important context (dates, categories)</li>
     *   <li>1-4: Supplementary information (counts, flags)</li>
     * </ul>
     *
     * @return Priority level (default: 5)
     */
    int priority() default 5;

    /**
     * Whether this field is required for context.
     *
     * <p>If true and the field is null, a warning is logged but processing continues.
     * If false, null values are simply omitted from context.</p>
     *
     * @return true if field is required (default: false)
     */
    boolean required() default false;

    /**
     * Tags to categorize this context field.
     *
     * <p>Useful for grouping or filtering context fields.
     * For example: {"metadata", "system"} or {"user-provided", "critical"}.</p>
     *
     * @return Array of tags (default: empty array)
     */
    String[] tags() default {};

    /**
     * Whether to sanitize this field for PII (Personally Identifiable Information).
     *
     * <p>If true, the PII detection service will scan and potentially redact
     * sensitive information before including in context.</p>
     *
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>User emails or names</li>
     *   <li>Phone numbers</li>
     *   <li>Address information</li>
     *   <li>Any field that might contain sensitive data</li>
     * </ul>
     *
     * @return true if PII sanitization should be applied (default: false)
     */
    boolean sanitizePII() default false;
}
