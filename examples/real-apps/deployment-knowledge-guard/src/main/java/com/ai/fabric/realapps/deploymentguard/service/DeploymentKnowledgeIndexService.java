package com.ai.fabric.realapps.deploymentguard.service;

import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.spi.RAGProvider;
import com.ai.fabric.realapps.deploymentguard.domain.DeploymentKnowledgeCatalog;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class DeploymentKnowledgeIndexService {

    public static final String VECTOR_SPACE = "deployment-knowledge";

    private final DeploymentKnowledgeCatalog catalog;
    private final RAGProvider ragProvider;
    private final VectorDatabaseService vectorDatabaseService;
    private final AtomicReference<IndexStatus> status = new AtomicReference<>(
        new IndexStatus("PENDING", 0, null, null)
    );

    public DeploymentKnowledgeIndexService(
        DeploymentKnowledgeCatalog catalog,
        RAGProvider ragProvider,
        VectorDatabaseService vectorDatabaseService
    ) {
        this.catalog = catalog;
        this.ragProvider = ragProvider;
        this.vectorDatabaseService = vectorDatabaseService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        seed();
    }

    public synchronized IndexStatus seed() {
        status.set(new IndexStatus("INDEXING", 0, Instant.now(), null));
        try {
            catalog.documents().forEach(document -> {
                vectorDatabaseService.removeVector(VECTOR_SPACE, document.id());
                ragProvider.indexContent(
                    VECTOR_SPACE,
                    document.id(),
                    document.content(),
                    document.vectorMetadata()
                );
            });
            IndexStatus complete = new IndexStatus(
                "READY",
                catalog.documents().size(),
                Instant.now(),
                null
            );
            status.set(complete);
            return complete;
        } catch (RuntimeException exception) {
            IndexStatus failed = new IndexStatus(
                "FAILED",
                0,
                Instant.now(),
                exception.getMessage()
            );
            status.set(failed);
            throw exception;
        }
    }

    public IndexStatus status() {
        return status.get();
    }

    public record IndexStatus(
        String state,
        int indexedDocuments,
        Instant updatedAt,
        String failure
    ) {}
}
