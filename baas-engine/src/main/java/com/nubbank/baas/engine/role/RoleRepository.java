package com.nubbank.baas.engine.role;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    java.util.List<Role> findByRoleScopeIn(java.util.Collection<String> scopes);
    java.util.Optional<Role> findByName(String name);
}
