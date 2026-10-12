-- A YIELD KEEPS THE HARNESS RUNNING (qits-1212).
--
-- A yield used to stop the agent's harness, so a workspace ran one harness at a time and V21 kept
-- one RUNNING agent per workspace. Now a yield only frees the slot: the harness keeps running, and
-- a yielded agent is RUNNING again at once when it has something to hear or works again, beside the
-- agent that took its slot. Two RUNNING agents in one workspace are therefore expected.
drop index if exists uq_agent_worktree_running;
