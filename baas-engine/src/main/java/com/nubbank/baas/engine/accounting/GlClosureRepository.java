package com.nubbank.baas.engine.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.*;

public interface GlClosureRepository extends JpaRepository<GlClosure, UUID> {
    Optional<GlClosure> findTopByOrderByClosingDateDesc();
    boolean existsByClosingDateGreaterThanEqual(LocalDate date);
    List<GlClosure> findAllByOrderByClosingDateDesc();
}
