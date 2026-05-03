package com.nubbank.baas.engine.share;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ShareProductRepository extends JpaRepository<ShareProduct, UUID> {
    Optional<ShareProduct> findByShortName(String shortName);
}
