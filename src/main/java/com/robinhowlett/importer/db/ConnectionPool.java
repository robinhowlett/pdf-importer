package com.robinhowlett.importer.db;

import com.robinhowlett.importer.ImportConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * HikariCP connection pool configured for bulk load. Session-level PostgreSQL settings
 * (synchronous_commit=off, work_mem) are applied via connectionInitSql to maximise throughput
 * without requiring superuser access or permanent config changes.
 */
public class ConnectionPool implements AutoCloseable {

    private final HikariDataSource dataSource;

    private ConnectionPool(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static ConnectionPool create(ImportConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.jdbcUrl());
        hc.setUsername(config.dbUser());
        hc.setPassword(config.dbPassword());
        // Threads × 2 + a few spares for coordination overhead
        hc.setMaximumPoolSize(config.threadCount() * 2 + 2);
        hc.setMinimumIdle(config.threadCount());
        hc.setConnectionTimeout(30_000);
        hc.setPoolName("pdf-importer");

        // Bulk-load session settings: async WAL flush and generous sort memory
        hc.setConnectionInitSql(
                "SET synchronous_commit = off; " +
                "SET work_mem = '64MB'; " +
                "SET maintenance_work_mem = '256MB'");

        return new ConnectionPool(new HikariDataSource(hc));
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
