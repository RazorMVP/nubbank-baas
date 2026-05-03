package com.nubbank.baas.engine.report;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ReportParameterRepository extends JpaRepository<ReportParameter, UUID> {}
