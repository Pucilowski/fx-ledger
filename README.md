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

Around them, deployment topology rather than services: `gateway/` (Caddy — the
single public entry point) and `console/` (a dependency-free static page the
gateway serves; the demo console).

## Stack

Java 21, SparkJava, PostgreSQL, jOOQ, Flyway, HikariCP, Testcontainers.
Deliberately no application framework: wiring is explicit and visible.

## Running

The whole system — both services, PostgreSQL, and a demo console behind a
Caddy gateway:

```sh
docker compose up --build      # then open http://localhost/
```

The console exercises every endpoint and shows the event stream live: create
accounts, deposit, get a firm quote (watch it expire), convert, and watch the
house position go short and the hedger flatten it — the remaining sliver is
the spread.

The gateway is the single public entry point (`/` console, `/ledger/*`,
`/fx/*`); the services and database stay on the private network. In a real
deployment this is where TLS, auth and rate limiting would live — point the
Caddyfile at a domain and TLS is automatic.

For development:

```sh
docker compose up postgres     # just the database, on :5433
./gradlew test                 # tests (Testcontainers manages its own database)
./gradlew :ledger-service:run  # ledger on :8080
./gradlew :fx-service:run      # fx on :8081
```

## Status / roadmap

Core flows complete:

1. ~~Accounts + deposits/withdrawals (ledger)~~ done
2. ~~Transfers with concurrency stress tests (ledger)~~ done
3. ~~Transactional outbox + publisher/reconciler + live/catch-up event feed (ledger)~~ done
4. ~~FX quotes: multi-provider aggregation, spread, signed quotes, expiry (fx)~~ done
5. ~~Conversions: quote-idempotent atomic execution (ledger)~~ done
6. ~~Position tracking as a stream projection + threshold netting/hedging (fx)~~ done

Known simplifications, in rough order of what a real system adds next:

- **Per-provider nostro settlement.** Hedges currently settle house ↔ external
  world directly; real books route them through per-provider nostro accounts,
  bringing funding, per-counterparty exposure limits and statement
  reconciliation with them. The account taxonomy already reserves the kind.
- **Consumer offset checkpointing.** fx-service rebuilds its position
  projection by replaying the stream from zero on restart; a checkpoint
  (offset + snapshot) would bound recovery time.
- **Authorization holds.** Two-phase balances (available vs settled) for
  card-style flows.
- **jOOQ code generation** from the migrations, replacing the string-based DSL.
- **Multi-instance publisher** (`for update skip locked` on the outbox sweep).
