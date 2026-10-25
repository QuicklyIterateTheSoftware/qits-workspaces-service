-- A RELEASE OF THE WRAPPER BRANCH NO LONGER ENDS AN OPEN ITEM'S AGENT (qits-1605).
--
-- A release that consumed an agent's wrapper branch used to make the agent OBSOLETE at once. But the
-- wrapper repository can release before the item's other repositories do, and before the item is
-- VERIFIED, so a still-working agent became OBSOLETE: its waits were refused or cancelled with no
-- wake, nothing nudged or woke it, and its item's views dropped it. An agent is OBSOLETE now only
-- once its branch was consumed by a release AND its item is closed (VERIFIED, DONE or DROPPED), in
-- whichever order the two happen.
--
-- branch_consumed_at is when a release consumed the agent's wrapper branch while it was ACTIVE;
-- consumed_version and consumed_commit are that release's version and commit, as it reported them,
-- carried on the AgentObsolete the scheduler announces once the item closes. All three null: no
-- release consumed the branch since the agent was last dispatched on it — a dispatch on the same
-- wrapper branch, or a revive, clears them, because the agent may have new unreleased work.
--
-- Nullable, no backfill, part of no constraint. Columns on agent_worktree, an entity that is already
-- a CausedRow: no ArchRulesTest decision.
alter table agent_worktree add column branch_consumed_at timestamp(6) with time zone;
alter table agent_worktree add column consumed_version text;
alter table agent_worktree add column consumed_commit text;
