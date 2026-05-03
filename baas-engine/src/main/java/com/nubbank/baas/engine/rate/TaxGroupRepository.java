package com.nubbank.baas.engine.rate;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TaxGroupRepository extends JpaRepository<TaxGroup, UUID> {}
