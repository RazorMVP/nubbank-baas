package com.nubbank.baas.engine.teller;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CashierRepository extends JpaRepository<Cashier, UUID> {
    List<Cashier> findByTellerId(UUID tellerId);
}
