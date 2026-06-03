package com.nubbank.baas.card.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import com.nubbank.baas.card.common.BaasException;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Service
public class PartnerJwtService {

    private final String secret;
    private final long expiryHours;

    public PartnerJwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiry-hours}") long expiryHours) {
        this.secret = secret;
        this.expiryHours = expiryHours;
    }

    public String issue(String userId, String email, String role,
                        String orgId, String orgName,
                        String schemaName, String tier, String environment) {
        try {
            MACSigner signer = new MACSigner(secret.getBytes(StandardCharsets.UTF_8));
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId)
                .claim("email", email)
                .claim("role", role)
                .claim("partner_id", orgId)
                .claim("org_name", orgName)
                .claim("schema_name", schemaName)
                .claim("tier", tier)
                .claim("environment", environment)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + expiryHours * 3_600_000L))
                .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new RuntimeException("Failed to issue JWT", ex);
        }
    }

    public PartnerContext validate(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            MACVerifier verifier = new MACVerifier(secret.getBytes(StandardCharsets.UTF_8));
            if (!jwt.verify(verifier)) {
                throw BaasException.unauthorized("INVALID_TOKEN", "Invalid JWT signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                throw BaasException.unauthorized("TOKEN_EXPIRED", "JWT has expired");
            }
            return new PartnerContext(
                claims.getStringClaim("partner_id"),
                claims.getStringClaim("schema_name"),
                claims.getStringClaim("tier"),
                claims.getStringClaim("environment"),
                "JWT",
                claims.getSubject()
            );
        } catch (BaasException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BaasException.unauthorized("INVALID_TOKEN", "Invalid or malformed JWT");
        }
    }
}
