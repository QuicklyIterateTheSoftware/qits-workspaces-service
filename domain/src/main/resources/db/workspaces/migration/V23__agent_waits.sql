-- DECLARED WAITS (qits-1153, W1-W8).
--
-- An agent says what it waits on: an event name and a selection over its payload (qits-eventstream's
-- EventSelection, stored as its JSON form). The durable listener matches arriving events against the
-- OPEN waits of that event name; a match or a timeout closes the wait and gives the agent a turn.
--
-- state: OPEN -> MATCHED (an event matched; matched_event_id names it)
--             -> TIMED_OUT (expires_at passed first)
--             -> CANCELLED (the agent or a person cancelled it, or the agent stopped being ACTIVE)
create table agent_wait (
  id               uuid         not null,
  agent_id         uuid         not null,
  label            text         not null,
  selection        text         not null,
  -- The selection's event name, copied out so the listener can narrow by it without parsing JSON.
  event_name       varchar(128) not null,
  state            varchar(16)  not null default 'OPEN'
                   check (state in ('OPEN','MATCHED','TIMED_OUT','CANCELLED')),
  created_at       timestamp(6) with time zone not null,
  expires_at       timestamp(6) with time zone,
  matched_event_id text,
  closed_at        timestamp(6) with time zone,
  causation_id     uuid,
  constraint pk_agent_wait primary key (id),
  constraint fk_agent_wait_agent foreign key (agent_id)
    references agent_worktree (id) on delete cascade,
  constraint ck_agent_wait_closed check ((state = 'OPEN') = (closed_at is null))
);

-- What the listener and the sweep read: the OPEN waits, by event name and by expiry.
create index ix_agent_wait_open_event on agent_wait (event_name) where state = 'OPEN';
create index ix_agent_wait_open_expiry on agent_wait (expires_at) where state = 'OPEN';
create index ix_agent_wait_agent on agent_wait (agent_id, created_at);
