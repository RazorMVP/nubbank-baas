package com.nubbank.baas.engine.teller;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.*;

public interface TellerSessionRepository extends JpaRepository<TellerSession, UUID> {
    Optional<TellerSession> findByCashierIdAndSessionDate(UUID cashierId, LocalDate date);
    List<TellerSession> findByTellerId(UUID tellerId);
}
