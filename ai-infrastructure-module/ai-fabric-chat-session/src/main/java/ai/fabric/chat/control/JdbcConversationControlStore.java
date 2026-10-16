package ai.fabric.chat.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Reference JDBC store for deployment-local conversation control.
 *
 * <p>Production hosts should create the tables through reviewed migrations and
 * construct this store with {@code initializeSchema=false}.</p>
 */
public final class JdbcConversationControlStore implements ConversationControlStore {

    private static final int MAX_ORCHESTRATION_STATE_JSON_CHARS = 262_144;

    private static final String CONTROL_COLUMNS = """
        tenant_id, deployment_id, conversation_id, owner_id, control_state,
        version, control_epoch, active_handover_id, channel_binding_id, updated_at
        """;
    private static final String MESSAGE_COLUMNS = """
        message_id, tenant_id, deployment_id, conversation_id, owner_id,
        message_sequence, participant_role, actor_ref, content,
        client_message_id, metadata_json, created_at
        """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public JdbcConversationControlStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        boolean initializeSchema
    ) {
        if (dataSource == null || objectMapper == null) {
            throw new IllegalArgumentException("dataSource and objectMapper are required");
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.objectMapper = objectMapper.copy();
        if (initializeSchema) {
            initializeSchema();
        }
    }

    @Override
    public ConversationControlSnapshot getOrCreate(ConversationScope scope, Instant now) {
        Optional<ConversationControlSnapshot> existing = find(scope);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            jdbc.update(
                """
                INSERT INTO ai_conversation_control (
                  tenant_id, deployment_id, conversation_id, owner_id,
                  control_state, version, control_epoch, updated_at
                ) VALUES (?, ?, ?, ?, ?, 0, 0, ?)
                """,
                scope.tenantId(), scope.deploymentId(), scope.conversationId(), scope.ownerId(),
                ConversationControlState.AI_ACTIVE.name(), timestamp(now)
            );
        } catch (DuplicateKeyException ignored) {
            // A concurrent creator won; ownership is checked by find below.
        }
        return find(scope).orElseThrow(() -> new IllegalStateException("conversation control row was not created"));
    }

    @Override
    public Optional<ConversationControlSnapshot> find(ConversationScope scope) {
        List<ConversationControlSnapshot> rows = jdbc.query(
            "SELECT " + CONTROL_COLUMNS + " FROM ai_conversation_control"
                + " WHERE tenant_id = ? AND deployment_id = ? AND conversation_id = ?",
            this::mapControl,
            scope.tenantId(), scope.deploymentId(), scope.conversationId()
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ConversationControlSnapshot snapshot = rows.getFirst();
        verifyOwner(scope, snapshot.scope().ownerId());
        return Optional.of(snapshot);
    }

    @Override
    public ConversationControlResult applyTransition(
        ConversationControlCommand command,
        ConversationControlState requiredState,
        ConversationControlState targetState,
        boolean advanceEpoch,
        Instant now
    ) {
        getOrCreate(command.scope(), now);
        ConversationControlResult result = transactions.execute(status -> {
            ConversationControlSnapshot current = lock(command.scope());
            Optional<ConversationControlResult> replay = findCommandResult(command);
            if (replay.isPresent()) {
                ConversationControlResult prior = replay.get();
                return new ConversationControlResult(
                    ConversationControlOutcome.IDEMPOTENT_REPLAY,
                    prior.snapshot(),
                    prior.code(),
                    prior.message()
                );
            }

            if (current.version() != command.expectedVersion()) {
                return storeCommandResult(
                    command,
                    current,
                    ConversationControlOutcome.VERSION_CONFLICT,
                    "CONVERSATION_VERSION_CONFLICT",
                    "Conversation control version changed before the command was applied.",
                    now
                );
            }
            if (current.state() == ConversationControlState.CLOSED
                || (requiredState != null && current.state() != requiredState)) {
                return storeCommandResult(
                    command,
                    current,
                    ConversationControlOutcome.INVALID_TRANSITION,
                    "CONVERSATION_TRANSITION_INVALID",
                    "The requested transition is not allowed from " + current.state() + ".",
                    now
                );
            }

            String activeHandoverId = nextHandoverId(command, current, targetState);
            String channelBindingId = nextChannelBinding(command, current, targetState);
            long nextVersion = current.version() + 1;
            long nextEpoch = current.controlEpoch() + (advanceEpoch ? 1 : 0);
            int updated = jdbc.update(
                """
                UPDATE ai_conversation_control
                   SET control_state = ?, version = ?, control_epoch = ?,
                       active_handover_id = ?, channel_binding_id = ?, updated_at = ?
                 WHERE tenant_id = ? AND deployment_id = ? AND conversation_id = ?
                   AND owner_id = ? AND version = ? AND control_state = ?
                """,
                targetState.name(), nextVersion, nextEpoch,
                activeHandoverId, channelBindingId, timestamp(now),
                command.scope().tenantId(), command.scope().deploymentId(),
                command.scope().conversationId(), command.scope().ownerId(),
                current.version(), current.state().name()
            );
            if (updated != 1) {
                throw new IllegalStateException("conversation control compare-and-set failed while row was locked");
            }
            ConversationControlSnapshot next = new ConversationControlSnapshot(
                command.scope(), targetState, nextVersion, nextEpoch,
                activeHandoverId, channelBindingId, now
            );
            if (targetState == ConversationControlState.CLOSED) {
                deleteOrchestrationState(command.scope());
            }
            return storeCommandResult(
                command,
                next,
                ConversationControlOutcome.APPLIED,
                "CONVERSATION_TRANSITION_APPLIED",
                "Conversation control transition applied.",
                now
            );
        });
        return java.util.Objects.requireNonNull(result, "transaction returned no result");
    }

    @Override
    public ConversationMessage appendMessage(ConversationMessageCommand command, Instant now) {
        getOrCreate(command.scope(), now);
        ConversationMessage result = transactions.execute(status -> {
            lock(command.scope());
            Optional<ConversationMessage> existing = findMessage(command);
            if (existing.isPresent()) {
                return verifyMessageReplay(command, existing.get());
            }
            Long maximum = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(message_sequence), 0)
                  FROM ai_conversation_message
                 WHERE tenant_id = ? AND deployment_id = ? AND conversation_id = ?
                """,
                Long.class,
                command.scope().tenantId(), command.scope().deploymentId(),
                command.scope().conversationId()
            );
            long sequence = (maximum == null ? 0 : maximum) + 1;
            try {
                jdbc.update(
                    """
                    INSERT INTO ai_conversation_message (
                      message_id, tenant_id, deployment_id, conversation_id, owner_id,
                      message_sequence, participant_role, actor_ref, content,
                      client_message_id, metadata_json, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    command.messageId(), command.scope().tenantId(), command.scope().deploymentId(),
                    command.scope().conversationId(), command.scope().ownerId(), sequence,
                    command.role().name(), command.actorRef(), command.content(),
                    command.clientMessageId(), writeJson(command.metadata()), timestamp(now)
                );
            } catch (DuplicateKeyException ex) {
                return findMessage(command)
                    .map(existingMessage -> verifyMessageReplay(command, existingMessage))
                    .orElseThrow(() -> new ConversationControlViolationException(
                        "MESSAGE_IDEMPOTENCY_CONFLICT",
                        "The message identity was already used by another scoped message."
                    ));
            }
            return new ConversationMessage(
                command.messageId(), command.scope(), sequence, command.role(),
                command.actorRef(), command.content(), command.clientMessageId(),
                command.metadata(), now
            );
        });
        return java.util.Objects.requireNonNull(result, "transaction returned no message");
    }

    @Override
    public List<ConversationMessage> listMessages(
        ConversationScope scope,
        long afterSequence,
        int limit
    ) {
        find(scope).orElseThrow(() -> new ConversationControlViolationException(
            "CONVERSATION_NOT_FOUND",
            "Conversation is not available."
        ));
        return List.copyOf(jdbc.query(
            "SELECT " + MESSAGE_COLUMNS + " FROM ai_conversation_message"
                + " WHERE tenant_id = ? AND deployment_id = ? AND conversation_id = ?"
                + " AND owner_id = ? AND message_sequence > ?"
                + " ORDER BY message_sequence ASC LIMIT ?",
            this::mapMessage,
            scope.tenantId(), scope.deploymentId(), scope.conversationId(), scope.ownerId(),
            afterSequence, limit
        ));
    }

    @Override
    public Optional<ConversationOrchestrationState> findOrchestrationState(
        ConversationScope scope,
        long expectedControlEpoch
    ) {
        return transactions.execute(status -> {
            verifyCanonicalAiTurn(lock(scope), expectedControlEpoch);
            return queryOrchestrationState(scope, false);
        });
    }

    @Override
    public ConversationOrchestrationState updateOrchestrationState(
        ConversationScope scope,
        long expectedControlEpoch,
        UnaryOperator<Map<String, Object>> mutation,
        Instant now
    ) {
        if (mutation == null) {
            throw new IllegalArgumentException("mutation is required");
        }
        ConversationOrchestrationState result = transactions.execute(status -> {
            verifyCanonicalAiTurn(lock(scope), expectedControlEpoch);
            ConversationOrchestrationState current = queryOrchestrationState(scope, true)
                .orElse(new ConversationOrchestrationState(scope, 0, Map.of(), now));
            Map<String, Object> updated = mutation.apply(new LinkedHashMap<>(current.metadata()));
            Map<String, Object> normalized = updated == null || updated.isEmpty()
                ? Map.of()
                : new LinkedHashMap<>(updated);
            long revision = current.revision() + 1;
            if (normalized.isEmpty()) {
                deleteOrchestrationState(scope);
                return new ConversationOrchestrationState(scope, revision, Map.of(), now);
            }
            String json = writeJson(normalized);
            if (json.length() > MAX_ORCHESTRATION_STATE_JSON_CHARS) {
                throw new ConversationControlViolationException(
                    "CONVERSATION_STATE_TOO_LARGE",
                    "Conversation orchestration state exceeds the configured bound."
                );
            }
            int updatedRows = jdbc.update(
                """
                UPDATE ai_conversation_orchestration_state
                   SET owner_id = ?, state_revision = ?, state_json = ?, updated_at = ?
                 WHERE tenant_id = ? AND deployment_id = ? AND conversation_id = ?
                """,
                scope.ownerId(), revision, json, timestamp(now),
                scope.tenantId(), scope.deploymentId(), scope.conversationId()
            );
            if (updatedRows == 0) {
                jdbc.update(
                    """
                    INSERT INTO ai_conversation_orchestration_state (
                      tenant_id, deployment_id, conversation_id, owner_id,
                      state_revision, state_json, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    scope.tenantId(), scope.deploymentId(), scope.conversationId(), scope.ownerId(),
                    revision, json, timestamp(now)
                );
            }
            return new ConversationOrchestrationState(scope, revision, normalized, now);
        });
        return java.util.Objects.requireNonNull(result, "transaction returned no orchestration state");
    }

    @Override
    public void clearOrchestrationState(ConversationScope scope, long expectedControlEpoch) {
        transactions.executeWithoutResult(status -> {
            verifyCanonicalAiTurn(lock(scope), expectedControlEpoch);
            deleteOrchestrationState(scope);
        });
    }

    private void verifyCanonicalAiTurn(
        ConversationControlSnapshot control,
        long expectedControlEpoch
    ) {
        if (expectedControlEpoch < 0) {
            throw new IllegalArgumentException("expectedControlEpoch cannot be negative");
        }
        if (control.state() != ConversationControlState.AI_ACTIVE
            || control.controlEpoch() != expectedControlEpoch) {
            throw new ConversationControlViolationException(
                "AI_TURN_LEASE_STALE",
                "Canonical conversation state requires the current AI control epoch."
            );
        }
    }

    private Optional<ConversationOrchestrationState> queryOrchestrationState(
        ConversationScope scope,
        boolean forUpdate
    ) {
        String sql = """
            SELECT owner_id, state_revision, state_json, updated_at
              FROM ai_conversation_orchestration_state
             WHERE tenant_id = ? AND deployment_id = ? AND conversation_id = ?
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<ConversationOrchestrationState> rows = jdbc.query(
            sql,
            (rs, row) -> {
                verifyOwner(scope, rs.getString("owner_id"));
                return new ConversationOrchestrationState(
                    scope,
                    rs.getLong("state_revision"),
                    readJson(rs.getString("state_json")),
                    instant(rs, "updated_at")
                );
            },
            scope.tenantId(), scope.deploymentId(), scope.conversationId()
        );
        return rows.stream().findFirst();
    }

    private void deleteOrchestrationState(ConversationScope scope) {
        jdbc.update(
            "DELETE FROM ai_conversation_orchestration_state"
                + " WHERE tenant_id = ? AND deployment_id = ? AND conversation_id = ? AND owner_id = ?",
            scope.tenantId(), scope.deploymentId(), scope.conversationId(), scope.ownerId()
        );
    }

    private ConversationControlSnapshot lock(ConversationScope scope) {
        List<ConversationControlSnapshot> rows = jdbc.query(
            "SELECT " + CONTROL_COLUMNS + " FROM ai_conversation_control"
                + " WHERE tenant_id = ? AND deployment_id = ? AND conversation_id = ? FOR UPDATE",
            this::mapControl,
            scope.tenantId(), scope.deploymentId(), scope.conversationId()
        );
        if (rows.isEmpty()) {
            throw new ConversationControlViolationException("CONVERSATION_NOT_FOUND", "Conversation is not available.");
        }
        ConversationControlSnapshot snapshot = rows.getFirst();
        verifyOwner(scope, snapshot.scope().ownerId());
        return snapshot;
    }

    private Optional<ConversationControlResult> findCommandResult(ConversationControlCommand command) {
        List<ConversationControlResult> rows = jdbc.query(
            """
            SELECT command_fingerprint, outcome, result_state, result_version, result_epoch,
                   result_handover_id, result_channel_binding_id, code, message, created_at
              FROM ai_conversation_control_command
             WHERE tenant_id = ? AND deployment_id = ? AND conversation_id = ?
               AND owner_id = ? AND idempotency_key = ?
            """,
            (rs, row) -> {
                if (!commandFingerprint(command).equals(rs.getString("command_fingerprint"))) {
                    throw new ConversationControlViolationException(
                        "CONTROL_COMMAND_IDEMPOTENCY_CONFLICT",
                        "The conversation-control idempotency key was reused with a different command."
                    );
                }
                return new ConversationControlResult(
                    ConversationControlOutcome.valueOf(rs.getString("outcome")),
                    new ConversationControlSnapshot(
                        command.scope(),
                        ConversationControlState.valueOf(rs.getString("result_state")),
                        rs.getLong("result_version"),
                        rs.getLong("result_epoch"),
                        rs.getString("result_handover_id"),
                        rs.getString("result_channel_binding_id"),
                        instant(rs, "created_at")
                    ),
                    rs.getString("code"),
                    rs.getString("message")
                );
            },
            command.scope().tenantId(), command.scope().deploymentId(),
            command.scope().conversationId(), command.scope().ownerId(), command.idempotencyKey()
        );
        return rows.stream().findFirst();
    }

    private ConversationControlResult storeCommandResult(
        ConversationControlCommand command,
        ConversationControlSnapshot snapshot,
        ConversationControlOutcome outcome,
        String code,
        String message,
        Instant now
    ) {
        jdbc.update(
            """
            INSERT INTO ai_conversation_control_command (
              tenant_id, deployment_id, conversation_id, owner_id, idempotency_key,
              command_type, command_fingerprint, outcome, result_state, result_version, result_epoch,
              result_handover_id, result_channel_binding_id, code, message, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            command.scope().tenantId(), command.scope().deploymentId(), command.scope().conversationId(),
            command.scope().ownerId(), command.idempotencyKey(), command.type().name(),
            commandFingerprint(command), outcome.name(), snapshot.state().name(),
            snapshot.version(), snapshot.controlEpoch(),
            snapshot.activeHandoverId(), snapshot.channelBindingId(), code, message, timestamp(now)
        );
        return new ConversationControlResult(outcome, snapshot, code, message);
    }

    private Optional<ConversationMessage> findMessage(ConversationMessageCommand command) {
        String sql;
        Object[] args;
        if (StringUtils.hasText(command.clientMessageId())) {
            sql = "SELECT " + MESSAGE_COLUMNS + " FROM ai_conversation_message"
                + " WHERE tenant_id = ? AND deployment_id = ? AND conversation_id = ?"
                + " AND owner_id = ? AND client_message_id = ?";
            args = new Object[] {
                command.scope().tenantId(), command.scope().deploymentId(), command.scope().conversationId(),
                command.scope().ownerId(), command.clientMessageId()
            };
        } else {
            sql = "SELECT " + MESSAGE_COLUMNS + " FROM ai_conversation_message"
                + " WHERE message_id = ? AND tenant_id = ? AND deployment_id = ?"
                + " AND conversation_id = ? AND owner_id = ?";
            args = new Object[] {
                command.messageId(), command.scope().tenantId(), command.scope().deploymentId(),
                command.scope().conversationId(), command.scope().ownerId()
            };
        }
        return jdbc.query(sql, this::mapMessage, args).stream().findFirst();
    }

    private ConversationMessage verifyMessageReplay(
        ConversationMessageCommand command,
        ConversationMessage existing
    ) {
        if (!existing.messageId().equals(command.messageId())
            || !java.util.Objects.equals(existing.clientMessageId(), command.clientMessageId())
            || existing.role() != command.role()
            || !existing.actorRef().equals(command.actorRef())
            || !existing.content().equals(command.content())
            || !existing.metadata().equals(command.metadata())) {
            throw new ConversationControlViolationException(
                "MESSAGE_IDEMPOTENCY_CONFLICT",
                "The message idempotency key was reused with different message data."
            );
        }
        return existing;
    }

    private String commandFingerprint(ConversationControlCommand command) {
        String canonical = String.join("\u001f",
            command.scope().tenantId(),
            command.scope().deploymentId(),
            command.scope().conversationId(),
            command.scope().ownerId(),
            command.type().name(),
            Long.toString(command.expectedVersion()),
            command.actorRef(),
            nullable(command.reasonCode()),
            nullable(command.handoverId()),
            nullable(command.channelBindingId())
        );
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String nullable(String value) {
        return value == null ? "" : value;
    }

    private String nextHandoverId(
        ConversationControlCommand command,
        ConversationControlSnapshot current,
        ConversationControlState target
    ) {
        if (command.type() == ConversationControlCommandType.REQUEST_HUMAN) {
            if (!StringUtils.hasText(command.handoverId())) {
                throw new IllegalArgumentException("REQUEST_HUMAN requires handoverId");
            }
            return command.handoverId();
        }
        return target == ConversationControlState.AI_ACTIVE || target == ConversationControlState.CLOSED
            ? null
            : current.activeHandoverId();
    }

    private String nextChannelBinding(
        ConversationControlCommand command,
        ConversationControlSnapshot current,
        ConversationControlState target
    ) {
        if (command.type() == ConversationControlCommandType.REQUEST_HUMAN) {
            if (!StringUtils.hasText(command.channelBindingId())) {
                throw new IllegalArgumentException("REQUEST_HUMAN requires channelBindingId");
            }
            return command.channelBindingId();
        }
        return target == ConversationControlState.AI_ACTIVE || target == ConversationControlState.CLOSED
            ? null
            : current.channelBindingId();
    }

    private ConversationControlSnapshot mapControl(ResultSet rs, int row) throws SQLException {
        ConversationScope scope = new ConversationScope(
            rs.getString("tenant_id"), rs.getString("deployment_id"),
            rs.getString("conversation_id"), rs.getString("owner_id")
        );
        return new ConversationControlSnapshot(
            scope,
            ConversationControlState.valueOf(rs.getString("control_state")),
            rs.getLong("version"), rs.getLong("control_epoch"),
            rs.getString("active_handover_id"), rs.getString("channel_binding_id"),
            instant(rs, "updated_at")
        );
    }

    private ConversationMessage mapMessage(ResultSet rs, int row) throws SQLException {
        ConversationScope scope = new ConversationScope(
            rs.getString("tenant_id"), rs.getString("deployment_id"),
            rs.getString("conversation_id"), rs.getString("owner_id")
        );
        return new ConversationMessage(
            rs.getString("message_id"), scope, rs.getLong("message_sequence"),
            ConversationParticipantRole.valueOf(rs.getString("participant_role")),
            rs.getString("actor_ref"), rs.getString("content"),
            rs.getString("client_message_id"), readJson(rs.getString("metadata_json")),
            instant(rs, "created_at")
        );
    }

    private void verifyOwner(ConversationScope requested, String storedOwner) {
        if (!requested.ownerId().equals(storedOwner)) {
            throw new ConversationControlViolationException(
                "CONVERSATION_OWNER_MISMATCH",
                "Conversation is not available."
            );
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("message metadata is not JSON serializable", ex);
        }
    }

    private Map<String, Object> readJson(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored message metadata is invalid", ex);
        }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? Instant.EPOCH : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private void initializeSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ai_conversation_control (
              tenant_id VARCHAR(128) NOT NULL,
              deployment_id VARCHAR(128) NOT NULL,
              conversation_id VARCHAR(160) NOT NULL,
              owner_id VARCHAR(256) NOT NULL,
              control_state VARCHAR(32) NOT NULL,
              version BIGINT NOT NULL,
              control_epoch BIGINT NOT NULL,
              active_handover_id VARCHAR(160),
              channel_binding_id VARCHAR(160),
              updated_at TIMESTAMP NOT NULL,
              PRIMARY KEY (tenant_id, deployment_id, conversation_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ai_conversation_control_command (
              tenant_id VARCHAR(128) NOT NULL,
              deployment_id VARCHAR(128) NOT NULL,
              conversation_id VARCHAR(160) NOT NULL,
              owner_id VARCHAR(256) NOT NULL,
              idempotency_key VARCHAR(200) NOT NULL,
              command_type VARCHAR(40) NOT NULL,
              command_fingerprint VARCHAR(64) NOT NULL,
              outcome VARCHAR(40) NOT NULL,
              result_state VARCHAR(32) NOT NULL,
              result_version BIGINT NOT NULL,
              result_epoch BIGINT NOT NULL,
              result_handover_id VARCHAR(160),
              result_channel_binding_id VARCHAR(160),
              code VARCHAR(100),
              message VARCHAR(1000),
              created_at TIMESTAMP NOT NULL,
              PRIMARY KEY (tenant_id, deployment_id, conversation_id, idempotency_key)
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ai_conversation_message (
              message_id VARCHAR(160) NOT NULL,
              tenant_id VARCHAR(128) NOT NULL,
              deployment_id VARCHAR(128) NOT NULL,
              conversation_id VARCHAR(160) NOT NULL,
              owner_id VARCHAR(256) NOT NULL,
              message_sequence BIGINT NOT NULL,
              participant_role VARCHAR(24) NOT NULL,
              actor_ref VARCHAR(256) NOT NULL,
              content VARCHAR(20000) NOT NULL,
              client_message_id VARCHAR(200),
              metadata_json VARCHAR(16000) NOT NULL,
              created_at TIMESTAMP NOT NULL,
              PRIMARY KEY (message_id),
              UNIQUE (tenant_id, deployment_id, conversation_id, message_sequence),
              UNIQUE (tenant_id, deployment_id, conversation_id, owner_id, client_message_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ai_conversation_orchestration_state (
              tenant_id VARCHAR(128) NOT NULL,
              deployment_id VARCHAR(128) NOT NULL,
              conversation_id VARCHAR(160) NOT NULL,
              owner_id VARCHAR(256) NOT NULL,
              state_revision BIGINT NOT NULL,
              state_json VARCHAR(262144) NOT NULL,
              updated_at TIMESTAMP NOT NULL,
              PRIMARY KEY (tenant_id, deployment_id, conversation_id),
              FOREIGN KEY (tenant_id, deployment_id, conversation_id)
                REFERENCES ai_conversation_control (tenant_id, deployment_id, conversation_id)
                ON DELETE CASCADE
            )
            """);
    }
}
