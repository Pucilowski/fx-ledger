package com.pucilowski.ledger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {

    private Database() {
    }

    public static DataSource connect(String jdbcUrl, String user, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        return new HikariDataSource(config);
    }

    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate();
    }

    /**
     * A dedicated, unpooled connection for LISTEN — it is held open
     * indefinitely, which would starve the pool.
     */
    public static Connection listenConnection(DataSource dataSource) throws SQLException {
        var hikari = (HikariDataSource) dataSource;
        return DriverManager.getConnection(
                hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
    }
}
