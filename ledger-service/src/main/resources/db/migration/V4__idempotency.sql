-- A journal executed on behalf of an external instruction (a signed quote, a
-- hedge execution) records the instruction's identity; the unique constraint
-- makes replaying the instruction impossible to double-apply — idempotency
-- enforced by the database, not by application bookkeeping.
alter table journal add column idempotency_key text;
alter table journal add constraint idempotency_key_unique unique (idempotency_key);
