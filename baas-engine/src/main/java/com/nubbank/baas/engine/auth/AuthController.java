package com.nubbank.baas.engine.auth;

import com.nubbank.baas.engine.auth.dto.*;
import com.nubbank.baas.engine.common.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/baas/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final PartnerOrganizationRepository orgRepo;
    private final PartnerUserRepository userRepo;
    private final PartnerJwtService jwtService;
    private final TenantProvisioningService provisioningService;
    private final BCryptPasswordEncoder passwordEncoder;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest req) {

        if (userRepo.existsByEmail(req.adminEmail())) {
            throw BaasException.conflict("EMAIL_TAKEN",
                "An account with this email already exists");
        }

        String partnerId = UUID.randomUUID().toString().replace("-", "");
        String schemaName = "partner_" + partnerId;

        PartnerOrganization org = PartnerOrganization.builder()
            .name(req.orgName())
            .status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX)
            .schemaName(schemaName)
            .contactEmail(req.adminEmail())
            .build();
        org = orgRepo.save(org);

        PartnerUser admin = PartnerUser.builder()
            .organization(org)
            .email(req.adminEmail())
            .passwordHash(passwordEncoder.encode(req.password()))
            .role("PARTNER_ADMIN")
            .build();
        userRepo.save(admin);

        // Provision schema asynchronously — the partner gets their token immediately
        provisioningService.provisionAsync(org.getId(), schemaName);

        String token = jwtService.issue(
            admin.getId().toString(), admin.getEmail(), admin.getRole(),
            org.getId().toString(), org.getName(),
            schemaName, org.getTier().name(), org.getEnvironment().name());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
            new AuthResponse(token, org.getId().toString(), schemaName,
                org.getTier().name(), org.getEnvironment().name(),
                admin.getRole(), org.getName())));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest req) {

        PartnerUser user = userRepo.findByEmailAndActiveTrue(req.email())
            .orElseThrow(() -> BaasException.unauthorized("INVALID_CREDENTIALS",
                "Invalid email or password"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw BaasException.unauthorized("INVALID_CREDENTIALS",
                "Invalid email or password");
        }

        PartnerOrganization org = user.getOrganization();
        String token = jwtService.issue(
            user.getId().toString(), user.getEmail(), user.getRole(),
            org.getId().toString(), org.getName(),
            org.getSchemaName(), org.getTier().name(), org.getEnvironment().name());

        return ResponseEntity.ok(ApiResponse.ok(
            new AuthResponse(token, org.getId().toString(), org.getSchemaName(),
                org.getTier().name(), org.getEnvironment().name(),
                user.getRole(), org.getName())));
    }
}
