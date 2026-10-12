-- A REFUSED AGENT START IS VISIBLE (qits-1217).
--
-- A workspace daemon that refuses to start an agent (409: another worktree holds the branch, no
-- harness sign-in) used to be logged once and nothing else: the row stayed RUNNING with no sign of
-- the failure while the scheduler tried again every pass. The refusal's detail and when it was first
-- seen are stored on the agent now, and cleared by the next start the daemon accepts.
--
-- Nullable, no backfill, part of no constraint: null means no start of this agent is refused.
alter table agent_worktree add column start_refusal text;
alter table agent_worktree add column start_refused_at timestamp(6) with time zone;
