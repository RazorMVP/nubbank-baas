package com.nubbank.baas.engine.share;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ShareTransactionRepository extends JpaRepository<ShareTransaction, UUID> {
    Page<ShareTransaction> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);
}
