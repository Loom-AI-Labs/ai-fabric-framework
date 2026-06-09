package com.ai.fabric.realapps.crm.repo;

import com.ai.fabric.realapps.crm.domain.CrmContact;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactRepository extends JpaRepository<CrmContact, Long> {}
