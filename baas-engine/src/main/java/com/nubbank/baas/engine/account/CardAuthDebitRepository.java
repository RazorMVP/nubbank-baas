package com.nubbank.baas.engine.account;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CardAuthDebitRepository extends JpaRepository<CardAuthDebit, UUID> {
    Optional<CardAuthDebit> findByAuthKey(String authKey);
}
