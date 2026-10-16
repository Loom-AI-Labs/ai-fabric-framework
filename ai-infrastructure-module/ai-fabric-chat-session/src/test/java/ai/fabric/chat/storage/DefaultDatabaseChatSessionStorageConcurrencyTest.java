package ai.fabric.chat.storage;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.SessionStatus;
import ai.fabric.chat.repository.ChatSessionRepository;
import ai.fabric.chat.util.ActionDraftMetadata;
import ai.fabric.chat.util.ConfirmationStack;
import ai.fabric.intent.action.PendingAction;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultDatabaseChatSessionStorageConcurrencyTest {

    private AnnotationConfigApplicationContext context;
    private DefaultDatabaseChatSessionStorage storage;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:chat-session-concurrency-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");

        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean("entityManagerFactory", LocalContainerEntityManagerFactoryBean.class, () -> {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("ai.fabric.chat.domain");
            HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
            adapter.setGenerateDdl(true);
            factory.setJpaVendorAdapter(adapter);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        });
        context.registerBean("transactionManager", JpaTransactionManager.class,
            () -> new JpaTransactionManager(context.getBean(EntityManagerFactory.class)));
        context.registerBean(DefaultDatabaseChatSessionStorage.class,
            () -> new DefaultDatabaseChatSessionStorage(context.getBean(ChatSessionRepository.class)));
        context.register(RepositoryConfiguration.class);
        context.refresh();

        storage = context.getBean(DefaultDatabaseChatSessionStorage.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void staleWholeSessionSaveCannotResurrectConsumedConfirmation() {
        PendingAction pending = pending("confirmable_action");
        Map<String, Object> metadata = new LinkedHashMap<>();
        ConfirmationStack.push(metadata, pending, 8);
        storage.save(session("conversation-1", "owner-1", metadata));

        ChatSession staleWriterSnapshot = storage.findById("conversation-1").orElseThrow();
        ChatSessionPendingActionStore pendingStore = new ChatSessionPendingActionStore(storage);

        assertThat(pendingStore.popPendingAction("conversation-1", "owner-1", pending)).contains(pending);
        assertThat(pendingStore.peekPendingAction("conversation-1", "owner-1")).isEmpty();

        Map<String, Object> staleMetadata = new LinkedHashMap<>(staleWriterSnapshot.getSessionMetadata());
        staleMetadata.put(ActionDraftMetadata.METADATA_KEY_DRAFT, Map.of("action", "another_action"));
        staleWriterSnapshot.setSessionMetadata(staleMetadata);

        assertThatThrownBy(() -> storage.save(staleWriterSnapshot))
            .isInstanceOf(OptimisticLockingFailureException.class);

        ChatSession persisted = storage.findById("conversation-1").orElseThrow();
        assertThat(ConfirmationStack.peek(persisted.getSessionMetadata())).isNull();
        assertThat(persisted.getSessionMetadata()).doesNotContainKey(ActionDraftMetadata.METADATA_KEY_DRAFT);
    }

    @Test
    void lockedDraftWriterPreservesPendingActionState() {
        storage.save(session("conversation-2", "owner-2", Map.of()));
        ChatSessionPendingActionStore pendingStore = new ChatSessionPendingActionStore(storage);
        ChatSessionActionDraftStore draftStore = new ChatSessionActionDraftStore(storage);
        PendingAction pending = pending("confirmable_action");

        pendingStore.pushPendingAction("conversation-2", "owner-2", pending);
        draftStore.saveDraft(
            "conversation-2",
            "owner-2",
            new ai.fabric.intent.actiondraft.ActionDraft(
                "draft_action",
                Map.of("id", "draft-1"),
                "missing required fields",
                Instant.parse("2026-10-16T00:00:00Z"),
                Instant.parse("2026-10-16T00:00:00Z")
            )
        );

        assertThat(pendingStore.peekPendingAction("conversation-2", "owner-2")).contains(pending);
        assertThat(draftStore.peekDraft("conversation-2", "owner-2"))
            .get()
            .extracting(ai.fabric.intent.actiondraft.ActionDraft::action)
            .isEqualTo("draft_action");
    }

    private ChatSession session(String id, String ownerId, Map<String, Object> metadata) {
        return ChatSession.builder()
            .id(id)
            .ownerId(ownerId)
            .status(SessionStatus.ACTIVE)
            .createdAt(LocalDateTime.parse("2026-10-16T00:00:00"))
            .lastInteractionAt(LocalDateTime.parse("2026-10-16T00:00:00"))
            .sessionMetadata(new LinkedHashMap<>(metadata))
            .build();
    }

    private PendingAction pending(String action) {
        return new PendingAction(
            action,
            Map.of("id", action + "-id"),
            "Confirm?",
            Instant.parse("2026-10-16T00:00:00Z")
        );
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaRepositories(basePackageClasses = ChatSessionRepository.class)
    static class RepositoryConfiguration {
    }
}
