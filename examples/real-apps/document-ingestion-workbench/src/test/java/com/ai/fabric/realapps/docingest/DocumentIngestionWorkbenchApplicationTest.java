package com.ai.fabric.realapps.docingest;

import ai.fabric.indexing.document.DocumentIndexingQueueAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentReaderFactory;
import com.ai.fabric.realapps.docingest.service.DocumentIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.profiles.active=smoke",
        "spring.datasource.url=jdbc:h2:mem:document_workbench_context;DB_CLOSE_DELAY=-1",
        "ai.indexing.sync-retry-worker.enabled=false",
        "ai.indexing.async-worker.enabled=false",
        "ai.indexing.batch-worker.enabled=false",
        "ai.indexing.cleanup.enabled=false"
    }
)
class DocumentIngestionWorkbenchApplicationTest {

    @Autowired
    private SpringAiDocumentReaderFactory readerFactory;

    @Autowired
    private SpringAiDocumentIndexingAdapter planningAdapter;

    @Autowired
    private DocumentIndexingQueueAdapter queueAdapter;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Test
    void executableApplicationWiresTheDocumentLifecycleBoundary() {
        assertThat(readerFactory).isNotNull();
        assertThat(planningAdapter).isNotNull();
        assertThat(queueAdapter).isNotNull();
        assertThat(ingestionService).isNotNull();
    }
}
