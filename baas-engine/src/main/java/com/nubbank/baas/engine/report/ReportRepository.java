package com.nubbank.baas.engine.report;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {
    Optional<Report> findByNameAndActiveTrue(String name);
    Page<Report> findByActiveTrue(Pageable pageable);
}
