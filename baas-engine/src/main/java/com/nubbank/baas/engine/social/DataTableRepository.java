package com.nubbank.baas.engine.social;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DataTableRepository extends JpaRepository<DataTableRegistration, UUID> {}
