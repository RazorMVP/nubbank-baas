package com.nubbank.baas.engine.system;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CodeRepository extends JpaRepository<Code, UUID> {
    Optional<Code> findByName(String name);
}
