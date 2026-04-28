package com.nubbank.baas.engine.teller;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TellerRepository extends JpaRepository<Teller, UUID> {}
