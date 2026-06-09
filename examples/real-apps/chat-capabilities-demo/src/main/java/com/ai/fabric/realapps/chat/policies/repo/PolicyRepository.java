package com.ai.fabric.realapps.chat.policies.repo;

import com.ai.fabric.realapps.chat.policies.domain.Policy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepository extends JpaRepository<Policy, Long> {
}
