package com.pucilowski.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The concurrency showpiece: many workers hammering transfers over a small,
 * highly contended set of accounts. Deterministic lock ordering means no
 * deadlocks (nothing but 201/422 comes back); locking plus single-transaction
 * journals mean money is conserved to the penny no matter the interleaving.
 */
@Testcontainers
class TransferStressTest {

    static final int ACCOUNTS = 4;
    static final int WORKERS = 8;
    static final int TRANSFERS_PER_WORKER = 200;
    static final String SEED_AMOUNT = "1000.00";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static App app;
    static HttpClient client;
    static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() {
        var dataSource = Database.connect(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        app = new App(dataSource, 0);
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        app.stop();
        client.close();
    }

    @Test
    void concurrentTransfersConserveMoneyWithoutDeadlocking() throws Exception {
        var accounts = new ArrayList<String>();
        for (int i = 0; i < ACCOUNTS; i++) {
            var account = newAccount("GBP");
            post("/accounts/" + account + "/deposits", """
                    {"amount": "%s"}""".formatted(SEED_AMOUNT));
            accounts.add(account);
        }

        var statuses = new ConcurrentLinkedQueue<Integer>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var workers = new ArrayList<Future<?>>();
            for (int w = 0; w < WORKERS; w++) {
                var random = new Random(w);
                workers.add(executor.submit(() -> {
                    for (int i = 0; i < TRANSFERS_PER_WORKER; i++) {
                        var from = accounts.get(random.nextInt(ACCOUNTS));
                        String to;
                        do {
                            to = accounts.get(random.nextInt(ACCOUNTS));
                        } while (to.equals(from));
                        var amount = BigDecimal.valueOf(random.nextInt(5000) + 1, 2);
                        try {
                            statuses.add(post("/transfers", """
                                    {"fromAccountId": "%s", "toAccountId": "%s", "amount": "%s"}"""
                                    .formatted(from, to, amount)).statusCode());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }));
            }
            for (var worker : workers) {
                worker.get();
            }
        }

        // Deadlock-freedom: every request completed as success or a clean
        // business rejection — nothing errored.
        assertThat(statuses).hasSize(WORKERS * TRANSFERS_PER_WORKER);
        assertThat(statuses).allMatch(status -> status == 201 || status == 422);
        assertThat(statuses).contains(201);

        // Conservation: the seeded total moved around but never changed.
        var total = BigDecimal.ZERO;
        for (var account : accounts) {
            var balance = balance(account);
            assertThat(balance).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            total = total.add(balance);
        }
        assertThat(total).isEqualByComparingTo(
                new BigDecimal(SEED_AMOUNT).multiply(BigDecimal.valueOf(ACCOUNTS)));

        // And the journal agrees with the cached balances.
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            var drifted = statement.executeQuery("""
                    select a.id
                    from account a
                    left join journal_entry e on e.account_id = a.id
                    group by a.id, a.balance
                    having a.balance <> coalesce(sum(e.amount), 0)
                    """);
            assertThat(drifted.next()).as("accounts whose balance drifted from the journal").isFalse();
        }
    }

    // -- helpers --

    static String newAccount(String currency) throws Exception {
        var response = post("/accounts", """
                {"ownerId": "%s", "currency": "%s"}""".formatted(UUID.randomUUID(), currency));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }

    static BigDecimal balance(String accountId) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/accounts/" + accountId))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new BigDecimal(json.readTree(response.body()).get("balance").asText());
    }

    static HttpResponse<String> post(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
