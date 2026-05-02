package com.nubbank.baas.engine.group;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface GroupRepository extends JpaRepository<Group, UUID> {
    Page<Group> findByStatus(GroupStatus status, Pageable pageable);
}
