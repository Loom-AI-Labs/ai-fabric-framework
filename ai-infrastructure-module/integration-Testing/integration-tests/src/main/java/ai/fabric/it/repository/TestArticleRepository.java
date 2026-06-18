package ai.fabric.it.repository;

import ai.fabric.it.entity.TestArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Test Article Entity
 *
 * Provides data access methods for testing AI infrastructure integration.
 *
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Repository
public interface TestArticleRepository extends JpaRepository<TestArticle, Long> {

    /**
     * Find articles by author
     */
    List<TestArticle> findByAuthor(String author);

    /**
     * Find published articles
     */
    List<TestArticle> findByPublishedTrue();

    /**
     * Find articles by title containing text
     */
    List<TestArticle> findByTitleContainingIgnoreCase(String title);

    /**
     * Find articles by content containing text
     */
    List<TestArticle> findByContentContainingIgnoreCase(String content);

    /**
     * Find articles by tags containing text
     */
    @Query("SELECT a FROM TestArticle a WHERE a.tags LIKE %:tag%")
    List<TestArticle> findByTagsContaining(@Param("tag") String tag);

    /**
     * Find articles published after date
     */
    List<TestArticle> findByPublishDateAfter(LocalDateTime date);

    /**
     * Find articles by read time range
     */
    @Query("SELECT a FROM TestArticle a WHERE a.readTime BETWEEN :minTime AND :maxTime")
    List<TestArticle> findByReadTimeRange(@Param("minTime") Integer minTime, @Param("maxTime") Integer maxTime);

    /**
     * Find articles with high view count
     */
    @Query("SELECT a FROM TestArticle a WHERE a.viewCount >= :minViews")
    List<TestArticle> findPopularArticles(@Param("minViews") Integer minViews);

    /**
     * Find recently published articles
     */
    @Query("SELECT a FROM TestArticle a WHERE a.publishDate >= :date")
    List<TestArticle> findRecentArticles(@Param("date") LocalDateTime date);

    /**
     * Find articles with summary
     */
    @Query("SELECT a FROM TestArticle a WHERE a.summary IS NOT NULL AND a.summary != ''")
    List<TestArticle> findArticlesWithSummary();

    /**
     * Count articles by author
     */
    long countByAuthor(String author);

    /**
     * Find articles by title and author
     */
    Optional<TestArticle> findByTitleAndAuthor(String title, String author);
}