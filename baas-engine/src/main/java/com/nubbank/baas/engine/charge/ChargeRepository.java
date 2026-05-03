package com.nubbank.baas.engine.charge;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ChargeRepository extends JpaRepository<Charge, UUID> {
    Page<Charge> findByActiveTrue(Pageable pageable);
}
