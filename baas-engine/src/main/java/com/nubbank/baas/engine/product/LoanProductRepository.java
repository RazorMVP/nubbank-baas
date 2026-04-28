package com.nubbank.baas.engine.product;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface LoanProductRepository extends JpaRepository<LoanProduct, UUID> {
    Optional<LoanProduct> findByShortName(String shortName);
    Page<LoanProduct> findByActiveTrue(Pageable pageable);
}
