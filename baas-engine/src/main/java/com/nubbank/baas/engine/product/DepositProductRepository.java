package com.nubbank.baas.engine.product;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface DepositProductRepository extends JpaRepository<DepositProduct, UUID> {
    Optional<DepositProduct> findByShortName(String shortName);
    Page<DepositProduct> findByActiveTrue(Pageable pageable);
}
