-- A PROJECT'S LONG-RUNNING AGENT WAITS TOO, ONE WORK ITEM PER WAIT (qits-2596, epic qits-2520).
--
-- A wait had two holders: an agent (agent_id) or a project's front desk (desk_project_id). A
-- project agent — the long-running agent qits-agents-service runs for a project, with no agent row
-- here — is the third. It is known only by its bearer: the principal name its commissioned token
-- carries (project_agent_subject, e.g. tok-project-agent-qits-developer) and that token's project
-- claim (project_agent_project_id, a qits-projects project uuid; no foreign key, the project lives
-- in another context's database). Every such wait names the work item it is held for (work_id, its
-- qits-projects uuid, and work_qualified_id, e.g. qits-123, as resolved at the declaration), so
-- the item reads "waiting on <label>" and idleness is judged per item.
--
-- ck_agent_wait_holder now holds exactly one of the three holders; ck_agent_wait_project_agent
-- that a project agent's wait names its project and its item. A closed project-agent wait is
-- announced as ProjectAgentWaitClosed (MATCHED, TIMED_OUT, BROKEN and CANCELLED alike) through
-- closed_event_id / announced_at, exactly as V32 records a desk's; closed_turn stays null, because
-- qits-agents-service renders the turn.
--
-- Columns on agent_wait, an entity that is already a CausedRow: no ArchRulesTest decision.
alter table agent_wait
  add column project_agent_subject varchar(128),
  add column project_agent_project_id uuid,
  add column work_id uuid,
  add column work_qualified_id varchar(64);
alter table agent_wait drop constraint ck_agent_wait_holder;
alter table agent_wait add constraint ck_agent_wait_holder check (
  (case when agent_id is not null then 1 else 0 end)
  + (case when desk_project_id is not null then 1 else 0 end)
  + (case when project_agent_subject is not null then 1 else 0 end) = 1);
alter table agent_wait add constraint ck_agent_wait_project_agent check (
  project_agent_subject is null
  or (project_agent_project_id is not null and work_id is not null and work_qualified_id is not null));
-- A project agent's OPEN waits on one item: what the per-item cap counts and the relay lists.
create index agent_wait_project_agent_open on agent_wait (project_agent_subject, work_id)
  where state = 'OPEN';
