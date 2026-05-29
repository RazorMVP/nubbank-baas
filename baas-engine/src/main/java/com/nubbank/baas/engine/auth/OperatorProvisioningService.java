package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.role.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Operator-side grant lifecycle. Revokes RBAC grants when an operator is removed (orphaned-grant mitigation). */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorProvisioningService {

    private final UserRoleRepository userRoleRepo;

    @Transactional
    public void revokeAllGrants(UUID subject) {
        userRoleRepo.deleteByUserId(subject);
        log.info("Revoked all RBAC grants for operator sub={}", subject);
    }
}
