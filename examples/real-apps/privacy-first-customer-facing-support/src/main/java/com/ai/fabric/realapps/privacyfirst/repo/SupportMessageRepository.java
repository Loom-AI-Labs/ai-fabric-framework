package com.ai.fabric.realapps.privacyfirst.repo;

import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {

    List<SupportMessage> findByCustomerId(String customerId);

    List<SupportMessage> findByCustomerIdStartingWithOrderByCreatedAtDesc(String customerIdPrefix);

    long countByCustomerIdStartingWith(String customerIdPrefix);

    long deleteByCustomerId(String customerId);

    long deleteByCustomerIdStartingWith(String customerIdPrefix);
}
