-- AN IDLE DISPATCHED AGENT IS NUDGED, ONCE (qits-1460).
--
-- A dispatched agent that ended its turn with no open wait and no block used to sit idle until a
-- person prompted it: nothing else wakes it. The scheduler now says one short turn to such an agent
-- once it has been quiet for qits.workspace.agent.nudge-after, and at most
-- qits.workspace.agent.nudge-limit times in a row.
--
-- nudged_at is when the last nudge was written (null: never). nudge_count is how many nudges in a
-- row went out with no other turn between them; any turn that is not a nudge (a dispatch
-- instruction, a delivery, a wait's wake) and a declared wait set it back to 0. A counter and not
-- "active since the last nudge", because every frame — the nudge's own delivery included — moves
-- last_activity_at.
--
-- Columns on agent_worktree, an entity that is already a CausedRow: no ArchRulesTest decision.
alter table agent_worktree add column nudged_at timestamp(6) with time zone;
alter table agent_worktree add column nudge_count integer not null default 0;
