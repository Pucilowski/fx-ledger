package com.pucilowski.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import spark.Response;
import spark.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class Api {

    private final Accounts accounts;
    private final Actions actions;

    Api(Accounts accounts, Actions actions) {
        this.accounts = accounts;
        this.actions = actions;
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

        http.exception(ValidationException.class, (e, request, response) -> fail(response, 400, e));
        http.exception(NotFoundException.class, (e, request, response) -> fail(response, 404, e));
        http.exception(ConflictException.class, (e, request, response) -> fail(response, 409, e));
        http.exception(InsufficientFundsException.class, (e, request, response) -> fail(response, 422, e));
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

    private static Map<String, Object> accountJson(Account account) {
        var json = new LinkedHashMap<String, Object>();
        json.put("id", account.id().toString());
        json.put("ownerId", account.ownerId().toString());
        json.put("currency", account.currency());
        json.put("balance", account.balance().toPlainString());
        return json;
    }

    private static BigDecimal amount(JsonNode body) {
        try {
            return new BigDecimal(Json.required(body, "amount"));
        } catch (NumberFormatException e) {
            throw new ValidationException("amount must be a decimal number");
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
