-- A PERSON STOPS AN AGENT (epic qits-1667).
--
-- run_state gains STOPPED: a person (or the desk) stopped the agent through POST /agents/{id}/stop.
-- The scheduler never places, resumes, nudges or drives a STOPPED agent; a wait that fires for it
-- appends its turn to pending_text, heard once the agent is restarted or prompted.
--
-- V21 declared the run_state check inline, so postgres named it <table>_<column>_check, as it named
-- V1's workspace_runtime_status_check (V12 drops that one by the same rule). It is replaced by a
-- named check that admits STOPPED.
alter table agent_worktree drop constraint if exists agent_worktree_run_state_check;
alter table agent_worktree add constraint ck_agent_worktree_run_state
  check (run_state in ('QUEUED','RUNNING','YIELDED','STOPPED'));

-- A STOPPED agent keeps its workspace_id when it had one (a RUNNING or YIELDED agent stopped), and
-- has none when it was stopped while still QUEUED: it gave up its queue place, and was never placed.
alter table agent_worktree drop constraint ck_agent_worktree_placed;
alter table agent_worktree add constraint ck_agent_worktree_placed
  check (run_state in ('QUEUED','STOPPED') or workspace_id is not null);
