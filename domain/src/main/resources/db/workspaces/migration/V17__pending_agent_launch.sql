-- A LAUNCH HELD FOR A QUEUED RUNNER WORKSPACE OUTLIVES THE PROCESS THAT HELD IT (qits-1064).
--
-- An agent dispatch (or delivery) onto a RUNNER row that is QUEUED for a slot is held until a runner
-- takes the row, and then waits for the row's daemon to answer. Both halves used to be in memory
-- only, on the premise that the caller holds the intent and "a re-press recovers it". That premise
-- did not hold: nothing tells qits-projects to re-press, so a restart while a row waited in line —
-- every deploy of this service — left the workspace with its goal and no agent, silently. So the
-- held launch is a row now, from the press until the daemon took it (DispatchService's class
-- javadoc, "A queued workspace holds its launch, in the table").
--
-- ONE ROW PER WORKSPACE, AND THAT IS THE ONE-LAUNCH RULE. The primary key is the workspace's id, so a
-- second hold for the same workspace is refused by the key (`insert … on conflict do nothing`)
-- rather than stacked behind the first — two held launches are how a ticket gets two agents.
--
-- `text` IS STORED, unlike every other instruction this service has carried. It is the one way the
-- launch can survive the process, and it lives exactly as long as the launch is undelivered: the row
-- is deleted once the daemon answered (or the wait gave up), when the workspace leaves the queue
-- other than by a take, and when the workspace resolves. It never becomes a column of `workspace`.
--
-- THE CLAIM. `claimed_by` names the process (one random id per boot) that took the row off the table
-- to deliver it, and `claimed_at` when. Unclaimed is "parked, anyone may take it when the row is
-- taken"; a claim is taken by a conditional update whose count is 1 for exactly one contender, so two
-- processes (a start-first deploy overlaps the old one with the new for a few seconds) never both
-- launch. A process that shuts down releases its claims; one that dies without doing so leaves a
-- claim that becomes takeable once it is older than twice the launch window, which no live wait
-- outlasts.
--
-- ON DELETE CASCADE is declared for completeness and never fires in the ordinary flow: a workspace row
-- is soft-deleted (V1), so the resolution deletes this row itself, in the resolving transaction.
--
-- @Uncaused on the entity, for workspace_prompt_draft's reason: the insert is native SQL that no
-- @PrePersist sees, and the row is rewritten by its claim.
create table pending_agent_launch (
  workspace_id  bigint                      not null,
  text          text                        not null,
  delivery      boolean                     not null,
  compact_first boolean                     not null,
  parked_at     timestamp(6) with time zone not null,
  claimed_by    varchar(64),
  claimed_at    timestamp(6) with time zone,
  constraint pk_pending_agent_launch primary key (workspace_id),
  constraint fk_pending_agent_launch_workspace foreign key (workspace_id)
    references workspace (id) on delete cascade,
  constraint ck_pending_agent_launch_claim
    check ((claimed_by is null) = (claimed_at is null))
);
