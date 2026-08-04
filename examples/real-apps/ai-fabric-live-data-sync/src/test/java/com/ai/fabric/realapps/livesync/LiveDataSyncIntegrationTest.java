package com.ai.fabric.realapps.livesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.fabric.examples.smoke.health.DemoHealthController;
import com.ai.fabric.realapps.livesync.service.DemoMutationService;
import com.ai.fabric.realapps.livesync.service.DemoStateService;
import com.ai.fabric.realapps.livesync.service.DemoWorkspaceService;
import com.ai.fabric.realapps.livesync.service.EntityKind;
import com.ai.fabric.realapps.livesync.service.LiveSyncChatService;
import com.ai.fabric.realapps.livesync.service.LiveSyncSearchService;
import com.ai.fabric.realapps.livesync.service.IndexingLifecycleLabService;
import com.ai.fabric.realapps.livesync.service.IndexingWorkProjectionService;
import com.ai.fabric.realapps.livesync.web.DemoModels.ChatRequest;
import com.ai.fabric.realapps.livesync.web.DemoModels.DemoState;
import com.ai.fabric.realapps.livesync.web.DemoModels.EntityRecord;
import com.ai.fabric.realapps.livesync.web.DemoModels.EntityUpdateRequest;
import java.math.BigDecimal;
import java.util.stream.IntStream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("smoke")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:live-sync-test;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.demo.workspace.cleanup-cron=0 0 0 1 1 *"
})
class LiveDataSyncIntegrationTest {

    @Autowired
    private DemoWorkspaceService workspaceService;

    @Autowired
    private DemoStateService stateService;

    @Autowired
    private DemoMutationService mutationService;

    @Autowired
    private LiveSyncChatService chatService;

    @Autowired
    private LiveSyncSearchService searchService;

    @Autowired
    private IndexingLifecycleLabService indexingLifecycleLabService;

    @Autowired
    private IndexingWorkProjectionService indexingWorkProjectionService;

    @Autowired
    private DemoHealthController demoHealthController;

    @RepeatedTest(5)
    void annotationLifecycleKeepsDatabaseAndVectorStateAligned() {
        String workspaceId = workspaceService.createWorkspace();

        DemoState seeded = stateService.state(workspaceId);
        assertThat(seeded.sourceTotal()).isEqualTo(6);
        assertThat(seeded.vectorTotal()).isEqualTo(6);
        assertThat(seeded.synchronizedTotal()).isEqualTo(6);

        var update = mutationService.update(
            workspaceId,
            EntityKind.PRODUCT,
            "novabook-air",
            new EntityUpdateRequest(
                "NovaBook Air",
                "A lightweight 14-inch notebook for mobile teams.",
                "The upgraded battery is rated for 26 hours of mixed office use. It includes 32 GB memory and a 1 TB SSD.",
                "Laptops",
                new BigDecimal("1399.00"),
                null,
                null,
                "PUBLISHED",
                null,
                null,
                null,
                null,
                null
            )
        );
        assertThat(update.metadata())
            .containsEntry("indexingWorkId", update.indexingWork().workId())
            .containsEntry("indexingStatus", "COMPLETED");
        assertThat(update.indexingWork().successfulTerminal()).isTrue();

        EntityRecord updated = entity(stateService.state(workspaceId), "novabook-air");
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(updated.vector().present()).isTrue();
        assertThat(updated.vector().inSync()).isTrue();
        assertThat(updated.vector().content()).contains("26 hours").doesNotContain("18 hours");
        assertThat(updated.vector().metadata()).containsEntry("workspaceId", workspaceId);
        assertThat(updated.vector().metadata())
            .containsEntry("version", 2)
            .doesNotContainKey("revision");
        var search = searchService.search(workspaceId, updated.vector().content(), 6);
        assertThat(search.hits())
            .extracting(hit -> hit.recordKey())
            .containsExactly("novabook-air");
        assertThat(search.hits().getFirst().metadata())
            .containsEntry("workspaceId", workspaceId);

        var delete = mutationService.delete(
            workspaceId,
            EntityKind.POLICY,
            "opened-electronics-return"
        );
        assertThat(delete.indexingWork().status()).isEqualTo("COMPLETED");

        DemoState afterDelete = stateService.state(workspaceId);
        assertThat(afterDelete.sourceTotal()).isEqualTo(5);
        assertThat(afterDelete.vectorTotal()).isEqualTo(5);
        assertThat(afterDelete.entities())
            .noneMatch(entity -> entity.recordKey().equals("opened-electronics-return"));
        assertThat(afterDelete.events().getFirst().operation()).isEqualTo("DELETE");
        assertThat(afterDelete.events().getFirst().inSync()).isTrue();

        workspaceService.resetWorkspace(workspaceId);

        DemoState reset = stateService.state(workspaceId);
        assertThat(reset.sourceTotal()).isEqualTo(6);
        assertThat(reset.vectorTotal()).isEqualTo(6);
        assertThat(reset.synchronizedTotal()).isEqualTo(6);
        assertThat(reset.events()).isEmpty();
    }

    @Test
    void healthIncludesProviderStorageAndIndexingReadiness() {
        var health = demoHealthController.health();

        assertThat(health)
            .containsEntry("status", "UP")
            .containsKey("provider")
            .containsKey("storage")
            .containsKey("indexingLifecycle");
        @SuppressWarnings("unchecked")
        var provider = (java.util.Map<String, Object>) health.get("provider");
        assertThat(provider)
            .containsEntry("generation", "smoke")
            .containsEntry("embeddings", "smoke")
            .containsEntry("generationReady", true)
            .containsEntry("embeddingReady", true);
    }

    @Test
    void workspacesRemainIsolatedAndChatUsesTheTypedAiFabricContract() {
        String firstWorkspace = workspaceService.createWorkspace();
        String secondWorkspace = workspaceService.createWorkspace();

        mutationService.update(
            firstWorkspace,
            EntityKind.GUIDE,
            "amber-synclight",
            new EntityUpdateRequest(
                "Amber SyncLight recovery",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "The desk hub flashes amber.",
                "Hold the reset control for 12 seconds, reconnect power, and wait for a steady blue light.",
                "Desk Hub",
                "MEDIUM"
            )
        );

        EntityRecord first = entity(stateService.state(firstWorkspace), "amber-synclight");
        EntityRecord second = entity(stateService.state(secondWorkspace), "amber-synclight");
        assertThat(first.vector().content()).contains("12 seconds");
        assertThat(second.vector().content()).contains("8 seconds").doesNotContain("12 seconds");

        var chat = chatService.query(
            firstWorkspace,
            new ChatRequest(first.vector().content(), null, "rag", "knowledge_sync")
        );
        assertThat(chat.conversationId()).startsWith("sync-chat-");
        assertThat(chat.result().type()).isEqualTo("INFORMATION_PROVIDED");
        assertThat(chat.result().success()).isTrue();
        assertThat(chat.result().data()).containsKey("documents");
        @SuppressWarnings("unchecked")
        var documents = (java.util.List<java.util.Map<String, Object>>) chat.result().data().get("documents");
        assertThat(documents).isNotEmpty();
        assertThat(documents)
            .allSatisfy(document -> {
                @SuppressWarnings("unchecked")
                var metadata = (java.util.Map<String, Object>) document.get("metadata");
                assertThat(metadata)
                    .containsEntry("workspaceId", firstWorkspace)
                    .doesNotContainValue(secondWorkspace);
            });
        assertThat(chat.result().metadata()).containsEntry("actionsEnabled", false);
    }

    @Test
    void workspaceScopedSearchRemainsReliableWithManyDuplicateWorkspaces() {
        IntStream.range(0, 8).forEach(ignored -> workspaceService.createWorkspace());
        String currentWorkspace = workspaceService.createWorkspace();
        EntityRecord currentGuide = entity(stateService.state(currentWorkspace), "amber-synclight");

        var search = searchService.search(
            currentWorkspace,
            currentGuide.vector().content(),
            1
        );

        assertThat(search.hits())
            .extracting(hit -> hit.recordKey())
            .containsExactly("amber-synclight");
        assertThat(search.hits().getFirst().metadata())
            .containsEntry("workspaceId", currentWorkspace);
    }

    @Test
    void publicWorkLifecycleProvesSupersessionRetryAndDeadLetter() {
        String workspaceId = workspaceService.createWorkspace();

        var superseded = indexingLifecycleLabService.start(
            workspaceId,
            "superseded",
            EntityKind.PRODUCT,
            "novabook-air"
        );
        var supersededTerminal = awaitTerminal(
            workspaceId,
            superseded.indexingWork().workId(),
            8_000
        );
        assertThat(supersededTerminal.status()).isEqualTo("SUPERSEDED");
        assertThat(entity(stateService.state(workspaceId), "novabook-air")
            .vector().content()).contains("18 hours");

        var recovery = indexingLifecycleLabService.start(
            workspaceId,
            "retry-recovery",
            EntityKind.GUIDE,
            "amber-synclight"
        );
        var recovered = awaitTerminal(
            workspaceId,
            recovery.indexingWork().workId(),
            10_000
        );
        assertThat(recovered.workId())
            .isEqualTo(recovery.indexingWork().workId());
        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(recovered.retryCount()).isEqualTo(1);

        var deadLetter = indexingLifecycleLabService.start(
            workspaceId,
            "dead-letter",
            EntityKind.POLICY,
            "expedited-shipping"
        );
        var failed = awaitTerminal(
            workspaceId,
            deadLetter.indexingWork().workId(),
            15_000
        );
        assertThat(failed.status()).isEqualTo("DEAD_LETTER");
        assertThat(failed.requiresOperatorReview()).isTrue();
        assertThat(failed.retryCount()).isEqualTo(failed.maxRetries());
        assertThat(failed.errorCode()).isEqualTo("ANALYSIS_PROVIDER_FAILED");
        assertThat(failed.deadLetterReason())
            .isEqualTo("ANALYSIS_PROVIDER_FAILED");
    }

    @Test
    void indexingWorkStatusIsBoundToTheOwningWorkspace() {
        String owner = workspaceService.createWorkspace();
        String other = workspaceService.createWorkspace();
        var mutation = mutationService.update(
            owner,
            EntityKind.PRODUCT,
            "novabook-air",
            new EntityUpdateRequest(
                "NovaBook Air",
                "Updated owner-only summary.",
                "20 hour battery and owner-scoped evidence.",
                "Laptops",
                new BigDecimal("1329.00"),
                null,
                null,
                "PUBLISHED",
                null,
                null,
                null,
                null,
                null
            )
        );

        assertThat(indexingWorkProjectionService.requireForWorkspace(
            owner,
            mutation.indexingWork().workId()
        ).status()).isEqualTo("COMPLETED");
        assertThatThrownBy(() ->
            indexingWorkProjectionService.requireForWorkspace(
                other,
                mutation.indexingWork().workId()
            )
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong");
    }

    private com.ai.fabric.realapps.livesync.web.DemoModels.IndexingWorkView
        awaitTerminal(
            String workspaceId,
            String workId,
            long timeoutMillis
        ) {
        long deadline = System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        com.ai.fabric.realapps.livesync.web.DemoModels.IndexingWorkView current;
        do {
            current = indexingWorkProjectionService.requireForWorkspace(
                workspaceId,
                workId
            );
            if (current.terminal()) {
                return current;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "Interrupted while waiting for indexing work",
                    exception
                );
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError(
            "Indexing work " + workId + " did not reach a terminal state; "
                + "last status was " + current.status()
        );
    }

    private EntityRecord entity(DemoState state, String recordKey) {
        return state.entities().stream()
            .filter(entity -> entity.recordKey().equals(recordKey))
            .findFirst()
            .orElseThrow();
    }
}
