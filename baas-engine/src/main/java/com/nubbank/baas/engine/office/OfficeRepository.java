package com.nubbank.baas.engine.office;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface OfficeRepository extends JpaRepository<Office, UUID> {}
