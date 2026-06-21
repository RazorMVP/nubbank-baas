package com.nubbank.baas.engine.role;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    List<UserRole> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);

    @org.springframework.data.jpa.repository.Query(
        "select p.code from UserRole ur join ur.role r join r.permissions p where ur.userId = :userId")
    java.util.List<String> findPermissionCodesByUserId(java.util.UUID userId);

    @org.springframework.data.jpa.repository.Query(
        "select r.name from UserRole ur join ur.role r where ur.userId = :userId")
    java.util.List<String> findRoleNamesByUserId(java.util.UUID userId);

    @org.springframework.data.jpa.repository.Query(
        "select count(ur) > 0 from UserRole ur join ur.role r where ur.userId = :userId and r.superuser = true")
    boolean existsSuperuserRoleByUserId(java.util.UUID userId);

    @org.springframework.data.jpa.repository.Query(
        "select count(distinct ur.userId) from UserRole ur join ur.role r where r.name = :roleName")
    long countDistinctUsersWithRole(String roleName);

    @org.springframework.data.jpa.repository.Query(
        "select ur.userId from UserRole ur join ur.role r where r.name = :roleName")
    java.util.List<java.util.UUID> findUserIdsByRoleName(String roleName);
}
