-- Transactional outbox: model events are written in the same transaction as
-- the journal they describe, so an event exists if and only if the change
-- committed. A publisher assigns the stream offset afterwards.
--
-- Why a separate published_offset instead of paging on id: bigserial values
-- can commit out of order (a transaction holding a lower id may commit after
-- one holding a higher id), so a consumer paging on id could permanently miss
-- an event. The single publisher assigns dense offsets in publish order;
-- consumers page on those.
create table event_log (
    id               bigserial   primary key,
    journal_id       uuid        not null references journal (id),
    model_type       text        not null,
    event_type       text        not null,
    payload          jsonb       not null,
    created_at       timestamptz not null default now(),
    published_offset bigint      unique,
    published_at     timestamptz
);

create index event_log_unpublished_idx on event_log (id) where published_offset is null;
