package com.nubbank.baas.engine.tenant;

import lombok.RequiredArgsConstructor;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class MultiTenantConfig {

    private final PartnerTenantResolver tenantResolver;
    private final PartnerSchemaProvider schemaProvider;

    @Bean
    public HibernatePropertiesCustomizer multiTenantHibernateCustomizer() {
        return hibernateProperties -> {
            hibernateProperties.put(
                AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantResolver);
            hibernateProperties.put(
                AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, schemaProvider);
        };
    }
}
