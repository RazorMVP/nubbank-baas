package com.nubbank.baas.engine.role;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Set;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
    Set<Permission> findByIdIn(Set<UUID> ids);

    @org.springframework.data.jpa.repository.Query("select p.code from Permission p")
    java.util.List<String> findAllCodes();
}
