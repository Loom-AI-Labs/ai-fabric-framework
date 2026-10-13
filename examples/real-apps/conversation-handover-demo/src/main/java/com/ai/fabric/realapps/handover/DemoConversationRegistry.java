package com.ai.fabric.realapps.handover;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
class DemoConversationRegistry {

    private final JdbcTemplate jdbc;

    DemoConversationRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.jdbc.execute("""
            CREATE TABLE IF NOT EXISTS demo_conversation_registry (
              conversation_id VARCHAR(160) PRIMARY KEY,
              owner_id VARCHAR(256) NOT NULL,
              safe_label VARCHAR(160) NOT NULL,
              handover_intake_requested BOOLEAN NOT NULL DEFAULT FALSE,
              handover_email VARCHAR(254),
              handover_name VARCHAR(120),
              handover_phone VARCHAR(40),
              handover_intake_at TIMESTAMP,
              created_at TIMESTAMP NOT NULL
            )
            """);
        this.jdbc.execute("ALTER TABLE demo_conversation_registry ADD COLUMN IF NOT EXISTS handover_intake_requested BOOLEAN NOT NULL DEFAULT FALSE");
        this.jdbc.execute("ALTER TABLE demo_conversation_registry ADD COLUMN IF NOT EXISTS handover_email VARCHAR(254)");
        this.jdbc.execute("ALTER TABLE demo_conversation_registry ADD COLUMN IF NOT EXISTS handover_name VARCHAR(120)");
        this.jdbc.execute("ALTER TABLE demo_conversation_registry ADD COLUMN IF NOT EXISTS handover_phone VARCHAR(40)");
        this.jdbc.execute("ALTER TABLE demo_conversation_registry ADD COLUMN IF NOT EXISTS handover_intake_at TIMESTAMP");
    }

    void create(String conversationId, String ownerId, String safeLabel, Instant now) {
        jdbc.update(
            "INSERT INTO demo_conversation_registry (conversation_id, owner_id, safe_label, created_at) VALUES (?, ?, ?, ?)",
            conversationId, ownerId, safeLabel, Timestamp.from(now)
        );
    }

    Entry require(String conversationId) {
        return find(conversationId).orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Conversation not found."
        ));
    }

    Optional<Entry> find(String conversationId) {
        List<Entry> rows = jdbc.query(
            """
            SELECT conversation_id, owner_id, safe_label, handover_intake_requested,
                   handover_email, handover_name, handover_phone, handover_intake_at, created_at
              FROM demo_conversation_registry
             WHERE conversation_id = ?
            """,
            (result, row) -> entry(result),
            conversationId
        );
        return rows.stream().findFirst();
    }

    List<Entry> list() {
        return List.copyOf(jdbc.query(
            """
            SELECT conversation_id, owner_id, safe_label, handover_intake_requested,
                   handover_email, handover_name, handover_phone, handover_intake_at, created_at
              FROM demo_conversation_registry
             ORDER BY created_at DESC
            """,
            (result, row) -> entry(result)
        ));
    }

    void markIntakeRequested(String conversationId) {
        jdbc.update(
            """
            UPDATE demo_conversation_registry
               SET handover_intake_requested = TRUE,
                   handover_email = NULL,
                   handover_name = NULL,
                   handover_phone = NULL,
                   handover_intake_at = NULL
             WHERE conversation_id = ?
            """,
            conversationId
        );
    }

    void saveIntake(
        String conversationId,
        DemoHandoverIntakePolicy.ValidatedIntake intake,
        Instant now
    ) {
        jdbc.update(
            """
            UPDATE demo_conversation_registry
               SET handover_intake_requested = FALSE,
                   handover_email = ?,
                   handover_name = ?,
                   handover_phone = ?,
                   handover_intake_at = ?
             WHERE conversation_id = ?
            """,
            intake.email(), intake.name(), intake.phone(), Timestamp.from(now), conversationId
        );
    }

    void clearIntake(String conversationId) {
        jdbc.update(
            """
            UPDATE demo_conversation_registry
               SET handover_intake_requested = FALSE,
                   handover_email = NULL,
                   handover_name = NULL,
                   handover_phone = NULL,
                   handover_intake_at = NULL
             WHERE conversation_id = ?
            """,
            conversationId
        );
    }

    private Entry entry(java.sql.ResultSet result) throws java.sql.SQLException {
        Timestamp intakeAt = result.getTimestamp("handover_intake_at");
        return new Entry(
            result.getString("conversation_id"),
            result.getString("owner_id"),
            result.getString("safe_label"),
            result.getBoolean("handover_intake_requested"),
            result.getString("handover_email"),
            result.getString("handover_name"),
            result.getString("handover_phone"),
            intakeAt == null ? null : intakeAt.toInstant(),
            result.getTimestamp("created_at").toInstant()
        );
    }

    record Entry(
        String conversationId,
        String ownerId,
        String safeLabel,
        boolean handoverIntakeRequested,
        String handoverEmail,
        String handoverName,
        String handoverPhone,
        Instant handoverIntakeAt,
        Instant createdAt
    ) {}
}
