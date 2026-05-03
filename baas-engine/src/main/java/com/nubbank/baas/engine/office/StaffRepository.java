package com.nubbank.baas.engine.office;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface StaffRepository extends JpaRepository<Staff, UUID> {
    Page<Staff> findByActiveTrue(Pageable pageable);
    Page<Staff> findByOfficeIdAndActiveTrue(UUID officeId, Pageable pageable);
}
