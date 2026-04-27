package com.nubbank.baas.engine.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public void provision(UUID partnerId, String schemaName) {
        // Full implementation in Task 8
        log.info("Provision stub called for schema: {}", schemaName);
    }

    @Async
    public void provisionAsync(UUID partnerId, String schemaName) {
        provision(partnerId, schemaName);
    }
}
