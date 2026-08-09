package com.pucilowski.ledger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

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
}
