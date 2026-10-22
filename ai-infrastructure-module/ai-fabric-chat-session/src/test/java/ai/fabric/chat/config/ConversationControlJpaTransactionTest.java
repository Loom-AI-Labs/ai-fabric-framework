package ai.fabric.chat.config;

import static org.assertj.core.api.Assertions.*;

import ai.fabric.chat.control.ConversationControlCommand;
import ai.fabric.chat.control.ConversationControlCommandType;
import ai.fabric.chat.control.ConversationControlGateway;
import ai.fabric.chat.control.ConversationControlOutcome;
import ai.fabric.chat.control.ConversationMessageCommand;
import ai.fabric.chat.control.ConversationParticipantRole;
import ai.fabric.chat.control.ConversationScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConversationControlJpaTransactionTest {
    private static final ConversationScope SCOPE = new ConversationScope("tenant", "deployment", "conversation", "owner");
    private static final List<String> TABLES = List.of("ai_conversation_control", "ai_conversation_control_command",
        "ai_conversation_message", "conversation_transaction_probe");

    @Test
    void configuredStoreJoinsJpaAndCommitsControlMessagesAndApplicationWritesTogether() {
        try (var fixture = new Fixture()) {
            fixture.context().run(context -> {
                assertThat(context).hasNotFailed();
                var gateway = context.getBean(ConversationControlGateway.class);
                new TransactionTemplate(fixture.manager).executeWithoutResult(status -> {
                    fixture.applicationWrite();
                    writeConversation(gateway);
                    assertCounts(fixture.jdbc, 1);
                    fixture.assertNotCommitted();
                });
                assertCounts(fixture.jdbc, 1);
                fixture.assertUnbound();
            });
        }
    }

    @Test
    void outerFailureRollsBackControlMessagesAndApplicationWritesWithoutLeakingACommit() {
        try (var fixture = new Fixture()) {
            fixture.context().run(context -> {
                var gateway = context.getBean(ConversationControlGateway.class);
                assertThatThrownBy(() -> new TransactionTemplate(fixture.manager).executeWithoutResult(status -> {
                    fixture.applicationWrite();
                    writeConversation(gateway);
                    assertCounts(fixture.jdbc, 1);
                    throw new IllegalStateException("application rejected handover");
                })).isInstanceOf(IllegalStateException.class).hasMessage("application rejected handover");
                assertCounts(fixture.jdbc, 0);
                fixture.assertUnbound();
            });
        }
    }

    private static void writeConversation(ConversationControlGateway gateway) {
        var result = gateway.transition(new ConversationControlCommand(SCOPE,
            ConversationControlCommandType.REQUEST_HUMAN, 0, "request", "owner", "USER_REQUESTED", "handover", "channel"));
        assertThat(result.outcome()).isEqualTo(ConversationControlOutcome.APPLIED);
        gateway.appendMessage(new ConversationMessageCommand(SCOPE, "message", "client-message",
            ConversationParticipantRole.USER, "owner", "Please help", Map.of()), null);
    }

    private static void assertCounts(JdbcTemplate jdbc, long expected) {
        for (String table : TABLES) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class))
            .as(table).isEqualTo(expected);
    }

    @Entity
    @Table(name = "conversation_transaction_probe")
    static class ApplicationRecord {
        @Id private String id;
        protected ApplicationRecord() { }
        ApplicationRecord(String id) { this.id = id; }
    }

    private static final class Fixture implements AutoCloseable {
        final JdbcDataSource source = new JdbcDataSource();
        final LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        final JpaTransactionManager manager;
        final JdbcTemplate jdbc;

        Fixture() {
            source.setURL("jdbc:h2:mem:control-jpa-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            factory.setDataSource(source);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan(ConversationControlJpaTransactionTest.class.getPackageName());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            factory.afterPropertiesSet();
            manager = new JpaTransactionManager(factory.getObject());
            jdbc = new JdbcTemplate(source);
        }

        ApplicationContextRunner context() {
            return new ApplicationContextRunner().withUserConfiguration(ConversationControlJdbcConfiguration.class)
                .withPropertyValues("ai.chat.control.enabled=true")
                .withBean(DataSource.class, () -> source)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(PlatformTransactionManager.class, () -> manager)
                .withBean(ConversationControlProperties.class, () -> {
                    var properties = new ConversationControlProperties();
                    properties.setInitializeSchema(true);
                    return properties;
                });
        }

        void applicationWrite() {
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
            entityManager.persist(new ApplicationRecord("record"));
            entityManager.flush();
        }

        void assertNotCommitted() {
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                for (String table : TABLES) {
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).as(table).isZero();
                    }
                }
            } catch (java.sql.SQLException failure) {
                throw new AssertionError(failure);
            }
        }

        void assertUnbound() {
            assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(factory.getObject())).isFalse();
        }

        @Override public void close() { factory.destroy(); jdbc.execute("SHUTDOWN"); }
    }
}
