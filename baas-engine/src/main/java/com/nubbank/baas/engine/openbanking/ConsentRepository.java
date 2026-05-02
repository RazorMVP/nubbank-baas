package com.nubbank.baas.engine.openbanking;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ConsentRepository extends JpaRepository<OpenBankingConsent, UUID> {
    Page<OpenBankingConsent> findByTppClientId(String tppClientId, Pageable pageable);
    List<OpenBankingConsent> findByStatusAndExpiryDateBefore(ConsentStatus status, LocalDate date);
}
