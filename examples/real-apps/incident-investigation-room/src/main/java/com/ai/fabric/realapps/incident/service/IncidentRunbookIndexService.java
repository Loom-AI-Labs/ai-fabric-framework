package com.ai.fabric.realapps.incident.service;

import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.spi.RAGProvider;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class IncidentRunbookIndexService {

    public static final String VECTOR_SPACE = "incident-runbook";

    private final IncidentRunbookCatalog catalog;
    private final RAGProvider ragProvider;
    private final VectorDatabaseService vectors;
    private final AtomicReference<IndexStatus> status = new AtomicReference<>(
        new IndexStatus("PENDING", 0, null, null)
    );

    public IncidentRunbookIndexService(
        IncidentRunbookCatalog catalog,
        RAGProvider ragProvider,
        VectorDatabaseService vectors
    ) {
        this.catalog = catalog;
        this.ragProvider = ragProvider;
        this.vectors = vectors;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        seed();
    }

    public synchronized IndexStatus seed() {
        status.set(new IndexStatus("INDEXING", 0, Instant.now(), null));
        try {
            catalog.documents().forEach(document -> {
                vectors.removeVector(VECTOR_SPACE, document.id());
                ragProvider.indexContent(
                    VECTOR_SPACE,
                    document.id(),
                    document.content(),
                    document.vectorMetadata()
                );
            });
            IndexStatus ready = new IndexStatus(
                "READY",
                catalog.documents().size(),
                Instant.now(),
                null
            );
            status.set(ready);
            return ready;
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
