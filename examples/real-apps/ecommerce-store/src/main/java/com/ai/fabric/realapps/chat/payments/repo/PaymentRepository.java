package com.ai.fabric.realapps.chat.payments.repo;

import com.ai.fabric.realapps.chat.payments.domain.Payment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Payment> findFirstByOrderIdOrderByCreatedAtDesc(Long orderId);
}
