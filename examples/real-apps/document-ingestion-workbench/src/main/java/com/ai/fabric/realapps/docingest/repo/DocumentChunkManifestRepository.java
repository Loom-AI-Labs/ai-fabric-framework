package com.ai.fabric.realapps.docingest.repo;

import com.ai.fabric.realapps.docingest.domain.DocumentChunkManifest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DocumentChunkManifestRepository
    extends JpaRepository<DocumentChunkManifest, Long> {

    List<DocumentChunkManifest> findBySourceIdOrderBySourceVersionAscChunkIndexAsc(
        String sourceId
    );

    List<DocumentChunkManifest> findBySourceIdAndSourceVersionOrderByChunkIndexAsc(
        String sourceId,
        long sourceVersion
    );

    List<DocumentChunkManifest> findBySourceIdAndStateOrderBySourceVersionAscChunkIndexAsc(
        String sourceId,
        DocumentChunkManifest.LifecycleState state
    );

    List<DocumentChunkManifest> findByStateOrderBySourceIdAscSourceVersionAscChunkIndexAsc(
        DocumentChunkManifest.LifecycleState state
    );

    List<DocumentChunkManifest> findBySourceIdAndStateInOrderBySourceVersionAscChunkIndexAsc(
        String sourceId,
        Collection<DocumentChunkManifest.LifecycleState> states
    );

    long countBySourceIdAndState(
        String sourceId,
        DocumentChunkManifest.LifecycleState state
    );

    void deleteBySourceIdAndSourceVersion(String sourceId, long sourceVersion);
}
