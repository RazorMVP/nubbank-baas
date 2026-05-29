package com.nubbank.baas.engine.auth.keycloak;

import com.nimbusds.jwt.SignedJWT;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.partner.PartnerOrganization;
import com.nubbank.baas.engine.partner.PartnerOrganizationRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Validates a Keycloak operator JWT and builds the tenant {@link PartnerContext}. */
@Service
@RequiredArgsConstructor
public class OperatorJwtResolver {

    private final OperatorJwtDecoderFactory decoderFactory;
    private final PartnerOrganizationRepository orgRepo;
    private final OperatorJwtProperties props;

    /**
     * Reads the {@code iss} claim WITHOUT verifying the signature, purely to route the token
     * to the right validator. Real cryptographic verification happens later in {@link #resolve}.
     * @return the issuer string if this token is parseable, else null.
     */
    public String peekIssuer(String token) {
        try { return SignedJWT.parse(token).getJWTClaimsSet().getIssuer(); }
        catch (Exception e) { return null; }
    }

    public boolean isAdminIssuer(String issuer) {
        return issuer != null && issuer.equals(props.adminIssuer());
    }

    public PartnerContext resolve(String token) {
        String issuer = peekIssuer(token);
        if (issuer == null) throw BaasException.unauthorized("INVALID_TOKEN", "Unparseable token");

        PartnerOrganization org = orgRepo.findByKeycloakIssuer(issuer)
            .orElseThrow(() -> BaasException.unauthorized("UNKNOWN_ISSUER", "Issuer not registered"));
        if (!org.getStatus().isActiveForAuth())
            throw BaasException.unauthorized("PARTNER_INACTIVE", "Partner not active for auth");

        Jwt jwt;
        try { jwt = decoderFactory.decoderFor(issuer).decode(token); }
        catch (Exception e) { throw BaasException.unauthorized("INVALID_TOKEN", "JWT validation failed"); }

        return new PartnerContext(
            org.getId().toString(),
            org.getSchemaName(),
            org.getTier().name(),
            org.getEnvironment().name(),
            "OPERATOR_JWT",
            jwt.getSubject()
        );
    }
}
