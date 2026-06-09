package com.ai.fabric.realapps.crm.repo;

import com.ai.fabric.realapps.crm.domain.CrmDeal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealRepository extends JpaRepository<CrmDeal, Long> {}
