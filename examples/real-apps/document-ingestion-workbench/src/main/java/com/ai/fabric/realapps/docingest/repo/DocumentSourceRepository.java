package com.ai.fabric.realapps.docingest.repo;

import com.ai.fabric.realapps.docingest.domain.DocumentSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentSourceRepository extends JpaRepository<DocumentSource, String> {
}
