package com.pucilowski.ledger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AppTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static App app;

    @BeforeAll
    static void start() {
        var dataSource = Database.connect(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        app = new App(dataSource, 0);
    }

    @AfterAll
    static void stop() {
        app.stop();
    }

    @Test
    void healthEndpointResponds() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + app.port() + "/health"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("OK");
        }
    }
}
