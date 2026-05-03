package com.nubbank.baas.engine.deposit;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FixedDepositRepository extends JpaRepository<FixedDepositAccount, UUID> {
    Page<FixedDepositAccount> findByCustomerId(UUID customerId, Pageable pageable);
    List<FixedDepositAccount> findByStatusAndMaturityDateBefore(FixedDepositStatus status, LocalDate date);
}
