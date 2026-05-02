package com.nubbank.baas.engine.common;

import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.ResultSetMetaData;
import java.util.*;

/**
 * Multi-tenant aware JdbcTemplate wrapper.
 *
 * Hibernate's MultiTenantConnectionProvider sets {@code search_path} for
 * Hibernate-managed sessions, but raw {@link JdbcTemplate} queries get
 * connections directly from the DataSource and bypass that routing.
 *
 * This wrapper sets {@code SET search_path TO &lt;tenant_schema&gt;, public}
 * on the connection before each query, using the schema from the current
 * {@link PartnerContext}. All non-Hibernate JDBC code (report engine, global
 * search, batch jobs) should use this instead of the raw {@code JdbcTemplate}.
 */
@Component
@RequiredArgsConstructor
public class TenantJdbcTemplate {

    private final JdbcTemplate jdbc;

    /**
     * Run a parameter-less query and return the rows as a list of column maps.
     * Sets {@code search_path} to the current tenant schema before executing.
     */
    public List<Map<String, Object>> queryForList(String sql) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "PartnerContext required for tenant query");
        String schema = PartnerContext.get().schemaName();
        return jdbc.execute((ConnectionCallback<List<Map<String, Object>>>) connection -> {
            try (var stmt = connection.createStatement()) {
                stmt.execute("SET search_path TO " + schema + ", public");
            }
            try (var stmt = connection.createStatement();
                 var rs = stmt.executeQuery(sql)) {
                List<Map<String, Object>> results = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
                    results.add(row);
                }
                return results;
            }
        });
    }

    /**
     * Run a parameterised query (positional ? placeholders).
     */
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "PartnerContext required for tenant query");
        String schema = PartnerContext.get().schemaName();
        return jdbc.execute((ConnectionCallback<List<Map<String, Object>>>) connection -> {
            try (var stmt = connection.createStatement()) {
                stmt.execute("SET search_path TO " + schema + ", public");
            }
            try (var ps = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (var rs = ps.executeQuery()) {
                    List<Map<String, Object>> results = new ArrayList<>();
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
                        results.add(row);
                    }
                    return results;
                }
            }
        });
    }
}
