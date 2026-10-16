package ai.fabric.chat.repository;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    @EntityGraph(attributePaths = "turns")
    Optional<ChatSession> findWithTurnsById(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ChatSession session where session.id = :id")
    Optional<ChatSession> findByIdForUpdate(@Param("id") String id);

    @EntityGraph(attributePaths = "turns")
    List<ChatSession> findWithTurnsByOwnerId(String ownerId);

    List<ChatSession> findByOwnerId(String ownerId);

    List<ChatSession> findByLastInteractionAtBeforeAndStatus(LocalDateTime cutoff, SessionStatus status);
}
