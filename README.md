# fx-ledger

A multi-currency ledger with FX conversion, built as an exploration of
PostgreSQL-backed event-driven patterns in modern Java — the kind of
architecture where the database is the backbone: source of truth,
transactional outbox, and event log, with no external message broker.

## Design goals

- **Correct money movement under concurrency.** Double-entry ledger rows,
  balances protected by row locks acquired in deterministic order, invariants
  enforced in both the domain and the schema (`check (balance >= 0)`).
- **Events without a broker.** State changes and their events commit in one
  transaction (transactional outbox); a publisher drains the outbox and a
  reconciler guarantees at-least-once delivery.
- **FX as a first-class flow.** Quotes aggregated from multiple simulated rate
  providers (best execution, per-provider circuit breakers), short-lived quote
  expiry, conversion executed as an atomic pair of ledger movements.
- **TDD against the real database.** Tests run against PostgreSQL via
  Testcontainers — no in-memory stand-ins for the component under test.

## Structure

Two services, one repo — split along "who owns what":

- **ledger-service** — owns all money movement: accounts, deposits and
  withdrawals, transfers, conversions. Single writer to the ledger; every
  movement is a balanced double-entry journal across customer, house, nostro
  and external accounts. Publishes events.
- **fx-service** — owns pricing and market risk: provider rate aggregation,
  firm short-lived quotes, net-position tracking and hedging. Consumes ledger
  events; never touches balances.

The seam: quotes are signed artifacts the ledger can verify locally, so the
customer execution path (`/convert`) makes no synchronous cross-service calls.

## Stack

Java 21, SparkJava, PostgreSQL, jOOQ, Flyway, HikariCP, Testcontainers.
Deliberately no application framework: wiring is explicit and visible.

## Running

```sh
docker compose up -d           # PostgreSQL on :5433
./gradlew test                 # tests (Testcontainers manages its own database)
./gradlew :ledger-service:run  # ledger on :8080
./gradlew :fx-service:run      # fx on :8081
```

## Status / roadmap

In order:

1. ~~Accounts + deposits/withdrawals (ledger)~~ done
2. ~~Transfers with concurrency stress tests (ledger)~~ done
3. ~~Transactional outbox + publisher/reconciler + live/catch-up event feed (ledger)~~ done
4. FX quotes: multi-provider aggregation, spread, signed quotes, expiry (fx)
5. Conversions: quote-idempotent atomic execution (ledger)
6. Position tracking as a stream projection + threshold hedging against
   nostro accounts (fx)
