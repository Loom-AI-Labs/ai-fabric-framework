package com.ai.fabric.realapps.chat.addresses.repo;

import com.ai.fabric.realapps.chat.addresses.domain.Address;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {

    List<Address> findByUserIdOrderByCreatedAtDesc(String userId);
}
