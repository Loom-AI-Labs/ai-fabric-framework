package com.ai.infrastructure.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AISearchable Annotation
 *
 * <p>Marks a field as searchable content that will be embedded and stored for semantic search.
 * This annotation identifies fields that should be processed for AI-powered search capabilities.</p>
 *
 * <p><strong>Purpose:</strong> Fields annotated with @AISearchable will be:
 * <ul>
 *   <li>Extracted and combined into searchable content</li>
 *   <li>Embedded using the configured embedding provider (ONNX, OpenAI, etc.)</li>
 *   <li>Stored in the vector database as the vector content</li>
 *   <li>Indexed in the vector database for semantic search</li>
 * </ul>
 * </p>
 *
 * <p><strong>Key Difference from @AIContext:</strong><br>
 * @AISearchable → Content that gets embedded and searched (description, title, content)<br>
 * @AIContext → Structured metadata for LLM context only (category, status, tags)
 * </p>
 *
 * <p><strong>Configuration Priority:</strong><br>
 * YAML configuration (ai-entity-config.yml) &gt; Annotation defaults &gt; Framework defaults
 * </p>
 *
 * <p><strong>Example Usage:</strong></p>
 * <pre>{@code
 * @Entity
 * @AICapable(entityType = "product")
 * public class Product {
 *     @Id
 *     private UUID id;
 *
 *     @AISearchable(weight = 2.0)  // Higher weight for titles
 *     private String name;
 *
 *     @AISearchable  // Default weight of 1.0
 *     private String description;
 *
 *     @AIContext  // Not embedded, but included in LLM context
 *     private String category;
 * }
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> Annotation processing is thread-safe.</p>
 *
 * <p><strong>Performance:</strong> Field values are extracted via reflection and cached
 * at the application level for optimal performance.</p>
 *
 * @author AI Infrastructure Team
 * @version 2.0.0
 * @since 2.0.0
 * @see com.ai.infrastructure.annotation.AIContext
 * @see com.ai.infrastructure.annotation.AICapable
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AISearchable {

    /**
     * Relative weight of this field in search relevance calculations.
     *
     * <p>Higher weights increase the importance of this field in search results.
     * For example, record titles might have weight 2.0 while descriptions have 1.0.</p>
     *
     * <p><strong>Typical Values:</strong></p>
     * <ul>
     *   <li>2.0-3.0: Primary fields (titles, names)</li>
     *   <li>1.0-1.5: Standard content (descriptions, summaries)</li>
     *   <li>0.5-1.0: Secondary content (tags, notes)</li>
     * </ul>
     *
     * @return The weight for this field (default: 1.0)
     */
    double weight() default 1.0;

    /**
     * Whether this field should be included in semantic search.
     *
     * <p>Set to false if the field should be stored but not used for search ranking.
     * This is useful for audit or display purposes.</p>
     *
     * @return true if field should be searchable (default: true)
     */
    boolean includeInSearch() default true;

    /**
     * Whether to include this field in RAG (Retrieval-Augmented Generation) context.
     *
     * <p>When generating LLM responses, fields with this flag will be included
     * in the context provided to the language model.</p>
     *
     * @return true if field should be included in RAG context (default: true)
     */
    boolean includeInRAG() default true;

    /**
     * Preprocessing strategy for this field before embedding.
     *
     * <p><strong>Available Strategies:</strong></p>
     * <ul>
     *   <li>none: Use raw field value</li>
     *   <li>normalize: Lowercase and trim whitespace</li>
     *   <li>clean: Remove special characters and extra spaces</li>
     *   <li>sanitize: Clean + remove PII if detected</li>
     * </ul>
     *
     * @return The preprocessing strategy (default: "normalize")
     */
    String preprocessing() default "normalize";

    /**
     * Maximum character length for this field.
     *
     * <p>If the field value exceeds this length, it will be truncated.
     * This prevents excessive token usage in embeddings.</p>
     *
     * <p>Set to -1 for no limit.</p>
     *
     * @return Maximum length in characters (default: 5000)
     */
    int maxLength() default 5000;

    /**
     * Whether this field is required for indexing.
     *
     * <p>If true and the field is null/empty, the entity will not be indexed.
     * If false, null/empty values are treated as empty strings.</p>
     *
     * @return true if field is required (default: false)
     */
    boolean required() default false;

    /**
     * Custom field name for this searchable content.
     *
     * <p>By default, uses the Java field name. Override this to customize
     * how the field appears in search results and metadata.</p>
     *
     * @return Custom field name (default: "" = use Java field name)
     */
    String fieldName() default "";

    /**
     * Tags to categorize this searchable field.
     *
     * <p>Useful for filtering or grouping search results by content type.
     * For example: {"primary", "user-facing"} or {"technical", "internal"}.</p>
     *
     * @return Array of tags (default: empty array)
     */
    String[] tags() default {};
}
