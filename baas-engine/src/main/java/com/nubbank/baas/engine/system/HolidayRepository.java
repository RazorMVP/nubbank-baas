package com.nubbank.baas.engine.system;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface HolidayRepository extends JpaRepository<Holiday, UUID> {}
