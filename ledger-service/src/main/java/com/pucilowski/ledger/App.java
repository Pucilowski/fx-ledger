package com.pucilowski.ledger;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import spark.Service;

import javax.sql.DataSource;

public final class App {

    private final Service http;
    private final EventPublisher publisher;

    public App(DataSource dataSource, int port) {
        this(dataSource, port, EventPublisher.Config.defaults());
    }

    public App(DataSource dataSource, int port, EventPublisher.Config publisherConfig) {
        Database.migrate(dataSource);
        var db = DSL.using(dataSource, SQLDialect.POSTGRES);
        this.publisher = new EventPublisher(db, dataSource, publisherConfig);
        this.publisher.start();

        this.http = Service.ignite().port(port);
        this.http.get("/health", (req, res) -> "OK");
        new Api(db, new Accounts(db), new Actions(db), publisher).routes(http);
        this.http.awaitInitialization();
    }

    public int port() {
        return http.port();
    }

    public void stop() {
        publisher.stop();
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
