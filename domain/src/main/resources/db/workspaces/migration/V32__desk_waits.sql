-- THE PROJECT FRONT DESK WAITS TOO (qits-1618).
--
-- A declared wait was an agent's alone. The project's front desk (a Claude session qits-projects
-- runs) has no agent row, so a wait now has one of two holders: an agent (agent_id, as before, with
-- its foreign key and cascade) or a project's desk (desk_project_id, the qits-projects project
-- uuid; not a foreign key, because the project lives in another context's database). Exactly one is
-- set, which ck_agent_wait_holder holds.
--
-- A desk wait closes as an agent's does (MATCHED, TIMED_OUT, CANCELLED), but nothing in this
-- service can be woken for it: its close is announced on the bus as DeskWaitClosed instead. That
-- announcement is published after the close commits, so the close records what is to be said in
-- the same transaction, and the sweep re-publishes whatever was never confirmed:
--
--   closed_event_id   the DeskWaitClosed event's id, set when a desk wait is MATCHED or TIMED_OUT
--                     (never on CANCELLED, which announces nothing). Every re-publish reuses it, and
--                     qits-events takes a repeated id as the same event.
--   closed_turn       the rendered turn the event carries, verbatim: it quotes values of the
--                     matched event as they were, which cannot be rendered again later.
--   announced_at      when the bus took the event (delivered, or held by its outbox). Null on a
--                     closed desk wait with a closed_event_id = still to be published.
--
-- Agent waits never set the three. Columns on agent_wait, an entity that is already a CausedRow: no
-- ArchRulesTest decision.
alter table agent_wait alter column agent_id drop not null;
alter table agent_wait add column desk_project_id uuid;
alter table agent_wait add column closed_event_id uuid;
alter table agent_wait add column closed_turn text;
alter table agent_wait add column announced_at timestamp(6) with time zone;
alter table agent_wait add constraint ck_agent_wait_holder
  check ((agent_id is null) <> (desk_project_id is null));

-- A desk's waits, as the door lists them and the per-project cap counts them.
create index ix_agent_wait_desk on agent_wait (desk_project_id, created_at)
  where desk_project_id is not null;
-- What the sweep re-publishes: closed desk waits whose announcement the bus never took.
create index ix_agent_wait_unannounced on agent_wait (closed_at)
  where closed_event_id is not null and announced_at is null;
