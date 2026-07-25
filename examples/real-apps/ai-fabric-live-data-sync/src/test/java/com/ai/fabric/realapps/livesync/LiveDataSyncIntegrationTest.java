package com.ai.fabric.realapps.livesync;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.fabric.realapps.livesync.service.DemoMutationService;
import com.ai.fabric.realapps.livesync.service.DemoStateService;
import com.ai.fabric.realapps.livesync.service.DemoWorkspaceService;
import com.ai.fabric.realapps.livesync.service.EntityKind;
import com.ai.fabric.realapps.livesync.service.LiveSyncChatService;
import com.ai.fabric.realapps.livesync.service.LiveSyncSearchService;
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
    "ai.fabric.examples.demo-health.enabled=false",
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

    @RepeatedTest(5)
    void annotationLifecycleKeepsDatabaseAndVectorStateAligned() {
        String workspaceId = workspaceService.createWorkspace();

        DemoState seeded = stateService.state(workspaceId);
        assertThat(seeded.sourceTotal()).isEqualTo(6);
        assertThat(seeded.vectorTotal()).isEqualTo(6);
        assertThat(seeded.synchronizedTotal()).isEqualTo(6);

        mutationService.update(
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

        EntityRecord updated = entity(stateService.state(workspaceId), "novabook-air");
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(updated.vector().present()).isTrue();
        assertThat(updated.vector().inSync()).isTrue();
        assertThat(updated.vector().content()).contains("26 hours").doesNotContain("18 hours");
        assertThat(updated.vector().metadata()).containsEntry("workspaceId", workspaceId);
        var search = searchService.search(workspaceId, updated.vector().content(), 6);
        assertThat(search.hits())
            .extracting(hit -> hit.recordKey())
            .containsExactly("novabook-air");
        assertThat(search.hits().getFirst().metadata())
            .containsEntry("workspaceId", workspaceId);

        mutationService.delete(workspaceId, EntityKind.POLICY, "opened-electronics-return");

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

    private EntityRecord entity(DemoState state, String recordKey) {
        return state.entities().stream()
            .filter(entity -> entity.recordKey().equals(recordKey))
            .findFirst()
            .orElseThrow();
    }
}
