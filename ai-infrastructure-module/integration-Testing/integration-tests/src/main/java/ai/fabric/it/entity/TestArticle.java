package ai.fabric.it.entity;

import ai.fabric.annotation.AICapable;
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
@AICapable
public class TestArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 100)
    private String author;

    @Column
    private String tags; // Comma-separated tags

    @Column
    private LocalDateTime publishDate;

    @Column
    private Integer readTime; // in minutes

    @Column
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