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
              created_at TIMESTAMP NOT NULL
            )
            """);
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
            "SELECT conversation_id, owner_id, safe_label, created_at FROM demo_conversation_registry WHERE conversation_id = ?",
            (result, row) -> new Entry(
                result.getString("conversation_id"),
                result.getString("owner_id"),
                result.getString("safe_label"),
                result.getTimestamp("created_at").toInstant()
            ),
            conversationId
        );
        return rows.stream().findFirst();
    }

    List<Entry> list() {
        return List.copyOf(jdbc.query(
            "SELECT conversation_id, owner_id, safe_label, created_at FROM demo_conversation_registry ORDER BY created_at DESC",
            (result, row) -> new Entry(
                result.getString("conversation_id"),
                result.getString("owner_id"),
                result.getString("safe_label"),
                result.getTimestamp("created_at").toInstant()
            )
        ));
    }

    record Entry(String conversationId, String ownerId, String safeLabel, Instant createdAt) {}
}
