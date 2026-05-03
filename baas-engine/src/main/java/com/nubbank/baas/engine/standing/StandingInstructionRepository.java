package com.nubbank.baas.engine.standing;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface StandingInstructionRepository extends JpaRepository<StandingInstruction, UUID> {
    Page<StandingInstruction> findByCustomerId(UUID customerId, Pageable pageable);
    List<StandingInstruction> findByStatus(String status);
}
