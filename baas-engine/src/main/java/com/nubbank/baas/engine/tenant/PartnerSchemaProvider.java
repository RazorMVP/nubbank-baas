package com.nubbank.baas.engine.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerSchemaProvider implements MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String schemaName) throws SQLException {
        Connection connection = getAnyConnection();
        try {
            if (!schemaName.matches("[a-zA-Z0-9_]+")) {
                throw new SQLException("Invalid schema name: " + schemaName);
            }
            connection.createStatement()
                .execute("SET search_path TO " + schemaName + ", public");
        } catch (SQLException ex) {
            releaseConnection(schemaName, connection);
            throw ex;
        }
        return connection;
    }

    @Override
    public void releaseConnection(String schemaName, Connection connection) throws SQLException {
        try {
            connection.createStatement().execute("SET search_path TO public");
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean supportsAggressiveRelease() { return false; }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) { return false; }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        throw new UnsupportedOperationException("Cannot unwrap PartnerSchemaProvider");
    }
}
