package com.nubbank.baas.engine.group;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface CenterRepository extends JpaRepository<Center, UUID> {}
