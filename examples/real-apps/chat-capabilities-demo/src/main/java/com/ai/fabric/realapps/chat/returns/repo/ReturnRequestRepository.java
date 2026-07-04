package com.ai.fabric.realapps.chat.returns.repo;

import com.ai.fabric.realapps.chat.returns.domain.ReturnRequest;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {

    List<ReturnRequest> findByUserIdOrderByCreatedAtDesc(String userId);

    long deleteByOrder_IdIn(Collection<Long> orderIds);
}
