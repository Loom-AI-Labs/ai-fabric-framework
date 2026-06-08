package com.ai.infrastructure.chat.repository;

import com.ai.infrastructure.chat.domain.ChatSession;
import com.ai.infrastructure.chat.domain.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    @EntityGraph(attributePaths = "turns")
    Optional<ChatSession> findWithTurnsById(String id);

    @EntityGraph(attributePaths = "turns")
    List<ChatSession> findWithTurnsByOwnerId(String ownerId);

    List<ChatSession> findByOwnerId(String ownerId);

    List<ChatSession> findByLastInteractionAtBeforeAndStatus(LocalDateTime cutoff, SessionStatus status);
}
