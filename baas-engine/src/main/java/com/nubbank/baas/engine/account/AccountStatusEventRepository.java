package com.nubbank.baas.engine.account;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AccountStatusEventRepository extends JpaRepository<AccountStatusEvent, UUID> {
    List<AccountStatusEvent> findByAccountIdOrderByChangedAtAsc(UUID accountId);
}
