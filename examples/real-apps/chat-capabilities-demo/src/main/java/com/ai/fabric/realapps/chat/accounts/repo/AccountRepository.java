package com.ai.fabric.realapps.chat.accounts.repo;

import com.ai.fabric.realapps.chat.accounts.domain.Account;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByUserId(String userId);

    boolean existsByUserId(String userId);
}
