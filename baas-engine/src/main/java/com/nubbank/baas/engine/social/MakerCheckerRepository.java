package com.nubbank.baas.engine.social;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface MakerCheckerRepository extends JpaRepository<MakerCheckerRequest, UUID> {
    Page<MakerCheckerRequest> findByStatus(String status, Pageable pageable);
}
