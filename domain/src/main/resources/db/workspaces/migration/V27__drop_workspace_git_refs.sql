-- A WORKSPACE NO LONGER STATES GIT REFS (qits-1385).
--
-- Since qits-1152 the push scope is the agent's: AgentService works it out per agent, stores it on
-- agent_worktree.git_refs (V21) and mints it into the agent's token. A workspace is a slot with no
-- branch, its credential states `gitRefs: []` and never pushes. So the per-workspace list V6 added,
-- and the narrowing that kept it current (contract C5, with its pending flag), have no writer and no
-- reader left.
--
-- Dropped rather than kept: nothing reads either column, and a live commission's list is qits-idp's
-- record, not this one. agent_worktree.git_refs stays.
alter table workspace drop column git_refs;
alter table workspace drop column git_refs_pending;
