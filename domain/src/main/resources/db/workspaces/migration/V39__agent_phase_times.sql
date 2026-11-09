-- AN AGENT'S LIFECYCLE PHASE (qits-2007).
--
-- run_state RUNNING only says the agent holds its slot: it is written at placement, before the
-- slot's container exists, and the agent's activity is null until its harness reports. So a reader
-- could not tell an agent whose slot is still provisioning from one that is idle. The agent views
-- now answer a phase (QUEUED, PROVISIONING, STARTING, READY) and since when, computed on read from
-- these three columns, the slot's runtime status and whether its daemon is connected.
--
-- placed_at is when the agent was last placed in its slot (QUEUED becomes RUNNING, or a stopped
-- agent's resume); started_at is when its harness was last started there (AgentStarted). Both are
-- cleared when it loses the slot (a requeue) or its harness (a stop); a yield keeps both, because a
-- yielded agent keeps its harness (qits-1212). queued_at is when it last became QUEUED.
--
-- Nullable, no backfill: the agents standing at migration read their phase from what this process
-- knows (whether it started them, what their daemon reports) until their next placement or start.
-- Columns on agent_worktree, an entity that is already a CausedRow: no ArchRulesTest decision.
alter table agent_worktree add column placed_at timestamp(6) with time zone;
alter table agent_worktree add column started_at timestamp(6) with time zone;
alter table agent_worktree add column queued_at timestamp(6) with time zone;
