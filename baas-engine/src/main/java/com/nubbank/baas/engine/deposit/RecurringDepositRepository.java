package com.nubbank.baas.engine.deposit;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface RecurringDepositRepository extends JpaRepository<RecurringDepositAccount, UUID> {
    Page<RecurringDepositAccount> findByCustomerId(UUID customerId, Pageable pageable);
}
