package com.nubbank.baas.engine.virtualaccount;

import com.nubbank.baas.engine.common.BaasException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualAccountService {

    private final VirtualAccountRepository poolRepo;
    private static final long LOW_POOL_THRESHOLD = 1_000L;

    /**
     * Assign the next available NUBAN from the pool to a partner schema.
     * PESSIMISTIC_WRITE lock prevents concurrent duplicate assignments.
     * Must be called within a transaction (caller or this method's @Transactional).
     */
    @Transactional
    public String assignNext(String schemaName) {
        VirtualAccountPool entry = poolRepo.findFirstUnassignedForUpdate()
            .orElseThrow(() -> BaasException.conflict("ACCOUNT_POOL_EXHAUSTED",
                "Virtual account pool is exhausted — contact platform support"));

        entry.setAssigned(true);
        entry.setAssignedToSchema(schemaName);
        entry.setAssignedAt(Instant.now());
        poolRepo.save(entry);

        long remaining = poolRepo.countByAssignedFalse();
        if (remaining < LOW_POOL_THRESHOLD) {
            log.warn("Virtual account pool is low: {} remaining", remaining);
        }

        return entry.getAccountNumber();
    }
}
