package com.nubbank.baas.engine.teller;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CashTransactionRepository extends JpaRepository<CashTransaction, UUID> {
    List<CashTransaction> findBySessionId(UUID sessionId);
}
