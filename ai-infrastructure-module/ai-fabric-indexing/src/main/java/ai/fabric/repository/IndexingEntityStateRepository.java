package ai.fabric.repository;

import ai.fabric.entity.IndexingEntityState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IndexingEntityStateRepository
    extends JpaRepository<IndexingEntityState, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT state FROM IndexingEntityState state WHERE state.stateKey = :stateKey")
    Optional<IndexingEntityState> findForUpdate(@Param("stateKey") String stateKey);
}
