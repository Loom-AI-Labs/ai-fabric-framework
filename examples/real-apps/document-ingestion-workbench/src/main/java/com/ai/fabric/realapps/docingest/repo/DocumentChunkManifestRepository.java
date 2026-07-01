package com.ai.fabric.realapps.docingest.repo;

import com.ai.fabric.realapps.docingest.domain.DocumentChunkManifest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentChunkManifestRepository extends JpaRepository<DocumentChunkManifest, Long> {

    List<DocumentChunkManifest> findBySourceIdOrderByChunkIndexAsc(String sourceId);

    long countBySourceId(String sourceId);

    void deleteBySourceId(String sourceId);
}
