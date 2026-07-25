package ai.fabric.it.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Test Article Entity for AI Infrastructure Integration Tests
 *
 * This entity represents an article that can be processed by the AI infrastructure.
 * It includes rich text content for testing AI analysis and content generation.
 *
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Entity
@Table(name = "test_articles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@AICapable(entityType = "test-article")
public class TestArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @AIIdentity
    private Long id;

    @Column(nullable = false, length = 500)
    @AISearchable(name = "title", priority = 100, required = true)
    private String title;

    @Column(columnDefinition = "TEXT")
    @AISearchable(name = "content", priority = 90)
    private String content;

    @Column(columnDefinition = "TEXT")
    @AISearchable(name = "summary", priority = 80)
    private String summary;

    @Column(length = 100)
    @AIContext(
        key = "author",
        destinations = {
            AIContextDestination.VECTOR_METADATA,
            AIContextDestination.API_RESPONSE
        }
    )
    private String author;

    @Column
    private String tags; // Comma-separated tags

    @Column
    @AIContext(
        key = "publishDate",
        dataType = AIContextDataType.DATE,
        destinations = {
            AIContextDestination.VECTOR_METADATA,
            AIContextDestination.API_RESPONSE
        }
    )
    private LocalDateTime publishDate;

    @Column
    private Integer readTime; // in minutes

    @Column
    @AIContext(
        key = "published",
        dataType = AIContextDataType.BOOLEAN,
        destinations = {
            AIContextDestination.VECTOR_METADATA,
            AIContextDestination.API_RESPONSE
        }
    )
    private Boolean published;

    @Column
    private Integer viewCount;

    @Column
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (publishDate == null) {
            publishDate = LocalDateTime.now();
        }
        if (viewCount == null) {
            viewCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Helper methods for testing
    public String getDisplayTitle() {
        return published ? title : "[DRAFT] " + title;
    }

    public List<String> getTagList() {
        if (tags == null || tags.trim().isEmpty()) {
            return List.of();
        }
        return List.of(tags.split(","));
    }

    public String getEstimatedReadTime() {
        return readTime != null ? readTime + " min read" : "Unknown";
    }

    public boolean isRecentlyPublished() {
        return publishDate != null &&
               publishDate.isAfter(LocalDateTime.now().minusDays(7));
    }
}
