package com.nubbank.baas.engine.role;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    List<UserRole> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}
