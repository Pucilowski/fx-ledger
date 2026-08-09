-- Accounts hold a balance in exactly one currency. A customer holding multiple
-- currencies has one account per currency (multi-currency wallet style).
create table account (
    id          uuid primary key,
    owner_id    uuid        not null,
    currency    char(3)     not null,
    balance     numeric(19, 4) not null default 0,
    created_at  timestamptz not null default now(),

    constraint balance_non_negative check (balance >= 0),
    constraint one_account_per_currency unique (owner_id, currency)
);

create index account_owner_idx on account (owner_id);
