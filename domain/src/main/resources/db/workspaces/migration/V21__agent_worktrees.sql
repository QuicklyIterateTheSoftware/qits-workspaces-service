-- AGENTS LEAVE THE WORKSPACE ROW (qits-1152, D14/D15/D22).
--
-- A workspace is a slot on a runner now: one container scoped to a project's wrapper. The work item,
-- the branches, the session and the agent credential move to an AGENT, one row per agent worktree
-- the workspace daemon hosts under /workspace/agents/<id>. The work columns on `workspace` stay,
-- unread by new rows, for the drain (D20); nothing is migrated.
--
-- state:     ACTIVE -> OBSOLETE (a release consumed its branch) -> REMOVED (worktrees and credential gone)
-- run_state: QUEUED (no slot yet) -> RUNNING (holds its workspace's one harness) <-> YIELDED
create table agent_worktree (
  id                         uuid         not null,
  workspace_id               bigint,
  repository_id              varchar(255) not null,
  work_id                    text         not null,
  entity_id                  text,
  ticket_id                  text,
  epic_id                    text,
  wrapper_branch             text         not null,
  harness                    varchar(16)  not null default 'CLAUDE'
                             check (harness in ('CLAUDE','KIMI')),
  admin                      boolean      not null default false,
  state                      varchar(16)  not null default 'ACTIVE'
                             check (state in ('ACTIVE','OBSOLETE','REMOVED')),
  run_state                  varchar(16)  not null default 'QUEUED'
                             check (run_state in ('QUEUED','RUNNING','YIELDED')),
  session_id                 text,
  command_id                 text,
  -- The facts qits-projects relays (title, status word, blocked), read by the daemon for the
  -- session name and by the cleanup sweep (VERIFIED, D4).
  entity_title               text,
  entity_status              text,
  entity_blocked             boolean,
  block_source               text,
  -- What is still to be said to the agent: its instruction until its first start, then delivered
  -- turns until the harness took them. Stored, so a restart loses none.
  pending_text               text,
  pending_compact            boolean      not null default false,
  -- The agent credential (D3, D18): a workspace-kind token with this agent's own Git refs.
  commissioned_token_id      varchar(64),
  commissioned_token_subject varchar(255),
  commissioned_token         text,
  git_refs                   text,
  waiting_since              timestamp(6) with time zone,
  last_activity_at           timestamp(6) with time zone,
  cleanup_prompted_at        timestamp(6) with time zone,
  obsolete_at                timestamp(6) with time zone,
  created_at                 timestamp(6) with time zone not null,
  removed_at                 timestamp(6) with time zone,
  causation_id               uuid,
  constraint pk_agent_worktree primary key (id),
  constraint fk_agent_worktree_workspace foreign key (workspace_id) references workspace (id),
  -- A QUEUED agent has no slot; a RUNNING or YIELDED one has (a REMOVED one keeps it as history).
  constraint ck_agent_worktree_placed
    check (run_state = 'QUEUED' or workspace_id is not null)
);

-- One ACTIVE agent per work item: dispatch answers it rather than making a second.
create unique index uq_agent_worktree_active_work on agent_worktree (work_id) where state = 'ACTIVE';

-- One running harness per workspace (D14).
create unique index uq_agent_worktree_running
  on agent_worktree (workspace_id) where run_state = 'RUNNING' and state <> 'REMOVED';

create index ix_agent_worktree_workspace on agent_worktree (workspace_id, state);

-- One row per (repository, branch) the agent pushed (D22). The repository is the git host's
-- repository id, mapped from the name the daemon reports.
create table agent_branch (
  agent_id      uuid         not null,
  repository_id varchar(255) not null,
  branch        text         not null,
  sha           varchar(64),
  pushed_at     timestamp(6) with time zone not null,
  constraint pk_agent_branch primary key (agent_id, repository_id, branch),
  constraint fk_agent_branch_agent foreign key (agent_id)
    references agent_worktree (id) on delete cascade
);

create index ix_agent_branch_repository on agent_branch (repository_id, branch);
