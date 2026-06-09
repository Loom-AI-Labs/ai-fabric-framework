package com.ai.fabric.realapps.crm.repo;

import com.ai.fabric.realapps.crm.domain.CrmAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<CrmAccount, Long> {}
