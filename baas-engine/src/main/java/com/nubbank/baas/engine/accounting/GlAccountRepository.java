package com.nubbank.baas.engine.accounting;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface GlAccountRepository extends JpaRepository<GlAccount, UUID> {
    Optional<GlAccount> findByGlCode(String glCode);
    Page<GlAccount> findByDisabledFalse(Pageable pageable);
}
