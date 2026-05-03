package com.nubbank.baas.engine.group;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface CenterGroupRepository extends JpaRepository<CenterGroup, UUID> {
    List<CenterGroup> findByCenterId(UUID centerId);
}
