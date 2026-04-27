package com.nubbank.baas.engine.virtualaccount;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface VirtualAccountRepository extends JpaRepository<VirtualAccountPool, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM VirtualAccountPool v WHERE v.assigned = false ORDER BY v.createdAt ASC LIMIT 1")
    Optional<VirtualAccountPool> findFirstUnassignedForUpdate();

    long countByAssignedFalse();
}
