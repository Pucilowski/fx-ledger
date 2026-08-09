package com.pucilowski.ledger;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import spark.Service;

import javax.sql.DataSource;

public final class App {

    private final Service http;

    public App(DataSource dataSource, int port) {
        Database.migrate(dataSource);
        var db = DSL.using(dataSource, SQLDialect.POSTGRES);

        this.http = Service.ignite().port(port);
        this.http.get("/health", (req, res) -> "OK");
        new Api(new Accounts(db), new Actions(db)).routes(http);
        this.http.awaitInitialization();
    }

    public int port() {
        return http.port();
    }

    public void stop() {
        http.stop();
        http.awaitStop();
    }

    public static void main(String[] args) {
        var dataSource = Database.connect(
                env("DB_URL", "jdbc:postgresql://localhost:5433/ledger"),
                env("DB_USER", "ledger"),
                env("DB_PASSWORD", "ledger"));
        new App(dataSource, Integer.parseInt(env("PORT", "8080")));
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value != null ? value : fallback;
    }
}
