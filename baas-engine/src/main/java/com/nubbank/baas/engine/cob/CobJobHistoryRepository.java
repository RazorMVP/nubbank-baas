package com.nubbank.baas.engine.cob;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface CobJobHistoryRepository extends JpaRepository<CobJobHistory, UUID> {
    Page<CobJobHistory> findByJobNameOrderByStartedAtDesc(String jobName, Pageable pageable);
    Page<CobJobHistory> findAllByOrderByStartedAtDesc(Pageable pageable);
}
