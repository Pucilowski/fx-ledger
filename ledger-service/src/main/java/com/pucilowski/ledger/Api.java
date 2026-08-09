package com.pucilowski.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.jooq.DSLContext;
import spark.Response;
import spark.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class Api {

    private final DSLContext db;
    private final Accounts accounts;
    private final Actions actions;
    private final EventPublisher publisher;

    Api(DSLContext db, Accounts accounts, Actions actions, EventPublisher publisher) {
        this.db = db;
        this.accounts = accounts;
        this.actions = actions;
        this.publisher = publisher;
    }

    void routes(Service http) {
        http.post("/accounts", (request, response) -> {
            var body = Json.parse(request.body());
            var ownerId = bodyUuid(body, "ownerId");
            var currency = Json.required(body, "currency");
            return created(response, accounts.create(ownerId, currency));
        });

        http.get("/accounts/:id", (request, response) -> {
            var account = accounts.get(pathUuid(request.params("id")));
            response.type("application/json");
            return Json.write(accountJson(account));
        });

        http.post("/accounts/:id/deposits", (request, response) -> {
            var accountId = pathUuid(request.params("id"));
            var amount = amount(Json.parse(request.body()));
            return created(response, actions.deposit(accountId, amount));
        });

        http.post("/accounts/:id/withdrawals", (request, response) -> {
            var accountId = pathUuid(request.params("id"));
            var amount = amount(Json.parse(request.body()));
            return created(response, actions.withdraw(accountId, amount));
        });

        http.post("/transfers", (request, response) -> {
            var body = Json.parse(request.body());
            var from = bodyUuid(body, "fromAccountId");
            var to = bodyUuid(body, "toAccountId");
            return created(response, actions.transfer(from, to, amount(body)));
        });

        http.post("/convert", (request, response) -> {
            var body = Json.parse(request.body());
            var quote = Quotes.fromJson(body.get("quote"));
            var from = bodyUuid(body, "fromAccountId");
            var to = bodyUuid(body, "toAccountId");
            return created(response, actions.convert(quote, from, to));
        });

        http.post("/hedge-settlements", (request, response) -> {
            var body = Json.parse(request.body());
            actions.hedgeSettlement(
                    Json.required(body, "hedgeId"),
                    Json.required(body, "providerId"),
                    Json.required(body, "fromCurrency"),
                    amountField(body, "fromAmount"),
                    Json.required(body, "toCurrency"),
                    amountField(body, "toAmount"));
            response.status(201);
            response.type("application/json");
            return Json.write(Map.of("status", "settled"));
        });

        // Catch-up stream: published events after an offset, in offset order.
        http.get("/events", (request, response) -> {
            var after = longParam(request.queryParams("after"), 0);
            var limit = (int) longParam(request.queryParams("limit"), 100);
            response.type("application/json");
            return Json.write(Map.of("events",
                    Events.after(db, after, limit).stream().map(Api::eventJson).toList()));
        });

        // Live stream over SSE. A consumer catches up via /events, then
        // follows here; the offsets let it stitch the two together.
        http.get("/events/stream", (request, response) -> {
            var queue = publisher.subscribe();
            try {
                var raw = response.raw();
                raw.setContentType("text/event-stream");
                raw.setCharacterEncoding("UTF-8");
                raw.setHeader("Cache-Control", "no-cache");
                raw.flushBuffer();
                var out = raw.getOutputStream();
                while (true) {
                    var event = queue.poll(15, TimeUnit.SECONDS);
                    if (event == null) {
                        out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    } else {
                        out.write(("id: " + event.offset() + "\ndata: " + Json.write(eventJson(event)) + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                    }
                    out.flush();
                }
            } catch (Exception e) {
                // subscriber disconnected
            } finally {
                publisher.unsubscribe(queue);
            }
            return "";
        });

        http.exception(ValidationException.class, (e, request, response) -> fail(response, 400, e));
        http.exception(NotFoundException.class, (e, request, response) -> fail(response, 404, e));
        http.exception(ConflictException.class, (e, request, response) -> fail(response, 409, e));
        http.exception(InsufficientFundsException.class, (e, request, response) -> fail(response, 422, e));
        http.exception(QuoteExpiredException.class, (e, request, response) -> fail(response, 422, e));
    }

    private static String created(Response response, Account account) {
        response.status(201);
        response.type("application/json");
        return Json.write(accountJson(account));
    }

    private static void fail(Response response, int status, RuntimeException e) {
        response.status(status);
        response.type("application/json");
        response.body(Json.error(e.getMessage()));
    }

    private static Map<String, Object> eventJson(Events.Event event) {
        var json = new LinkedHashMap<String, Object>();
        json.put("offset", event.offset());
        json.put("modelType", event.modelType());
        json.put("eventType", event.eventType());
        json.put("occurredAt", event.occurredAt().toString());
        try {
            json.put("payload", Json.MAPPER.readTree(event.payload()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return json;
    }

    private static long longParam(String raw, long fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ValidationException("expected a number, got " + raw);
        }
    }

    private static Map<String, Object> accountJson(Account account) {
        var json = new LinkedHashMap<String, Object>();
        json.put("id", account.id().toString());
        json.put("ownerId", account.ownerId().toString());
        json.put("currency", account.currency());
        json.put("balance", account.balance().toPlainString());
        return json;
    }

    private static BigDecimal amount(JsonNode body) {
        return amountField(body, "amount");
    }

    private static BigDecimal amountField(JsonNode body, String field) {
        try {
            return new BigDecimal(Json.required(body, field));
        } catch (NumberFormatException e) {
            throw new ValidationException(field + " must be a decimal number");
        }
    }

    private static UUID bodyUuid(JsonNode body, String field) {
        try {
            return UUID.fromString(Json.required(body, field));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(field + " must be a uuid");
        }
    }

    private static UUID pathUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("no account " + raw);
        }
    }
}
