-- Account kinds: 'customer' money we hold for users, 'house' the firm's own
-- position, 'nostro' the firm's money held at external counterparties, and
-- 'external' the outside world — the source/sink for money entering or
-- leaving the system.
alter table account add column kind text not null default 'customer';
alter table account add constraint kind_valid
    check (kind in ('customer', 'house', 'nostro', 'external'));

-- The outside world may owe or be owed arbitrary amounts, and the house may
-- run a short position in a currency; customer and nostro accounts must stay
-- non-negative.
alter table account drop constraint balance_non_negative;
alter table account add constraint balance_non_negative
    check (kind in ('external', 'house') or balance >= 0);

-- Every money movement is a journal of entries that balance to zero per
-- currency: money moves between accounts, it is never created or destroyed.
-- Balancing is enforced by the application and verified by tests; the schema
-- can only enforce it with deferred triggers, which we avoid for now.
create table journal (
    id          uuid        primary key,
    type        text        not null,
    created_at  timestamptz not null default now()
);

create table journal_entry (
    id          uuid    primary key,
    journal_id  uuid    not null references journal (id),
    account_id  uuid    not null references account (id),
    currency    char(3) not null,
    amount      numeric(19, 4) not null,

    constraint amount_non_zero check (amount <> 0)
);

create index journal_entry_journal_idx on journal_entry (journal_id);
create index journal_entry_account_idx on journal_entry (account_id);
