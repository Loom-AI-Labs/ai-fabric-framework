package ai.fabric.governance.catalog.jpa;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface IndexCatalogRepository extends JpaRepository<IndexCatalogEntity, String> {

    boolean existsByEntityTypeAndEntityId(String entityType, String entityId);

    void deleteByEntityTypeAndEntityId(String entityType, String entityId);

    void deleteByEntityType(String entityType);

    @Query("""
        select e from IndexCatalogEntity e
        where e.entityType = :entityType
        and (:updatedAfter is null or e.indexedUpdatedAt > :updatedAfter)
        order by e.indexedUpdatedAt asc, e.key asc
        """)
    List<IndexCatalogEntity> scanByEntityType(
        @Param("entityType") String entityType,
        @Param("updatedAfter") LocalDateTime updatedAfter,
        Pageable pageable
    );

    @Query("""
        select e from IndexCatalogEntity e
        where e.metadataJson is not null
        and e.metadataJson like concat('%', :snippet, '%')
        order by e.indexedUpdatedAt desc, e.key desc
        """)
    List<IndexCatalogEntity> findByMetadataSnippet(
        @Param("snippet") String snippet,
        Pageable pageable
    );
}
