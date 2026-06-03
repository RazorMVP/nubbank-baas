package com.nubbank.baas.card.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class PartnerTenantResolver implements CurrentTenantIdentifierResolver<String> {

    @Override
    public String resolveCurrentTenantIdentifier() {
        return Optional.ofNullable(PartnerContext.get())
            .map(PartnerContext::schemaName)
            .orElse("public");
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
