package com.nubbank.baas.engine.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AccountingRuleRepository extends JpaRepository<AccountingRule, UUID> {}
