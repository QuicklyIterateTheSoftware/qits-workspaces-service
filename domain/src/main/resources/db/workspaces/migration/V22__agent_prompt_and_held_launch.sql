-- THE HELD LAUNCH AND THE PROMPT DRAFT MOVE TO THE AGENT (qits-1152).
--
-- pending_agent_launch held a launch for a QUEUED workspace; the agent row's pending_text holds what
-- is still to be said now, so the table has no reader left.
drop table pending_agent_launch;

-- The prompt draft and its images belong to an agent now (D12). Composition state, not a record:
-- dropped with the workspace key and started again on the agent's.
drop table workspace_prompt_attachment;
drop table workspace_prompt_draft;

create table agent_prompt_draft (
  agent_id                uuid         not null,
  content                 text         not null,
  serialized_prompt       text,
  prompt_version          bigint       not null default 0,
  last_run_at             timestamp(6),
  last_run_prompt_version bigint,
  last_run_command_id     varchar(255),
  updated_at              timestamp(6) not null,
  constraint pk_agent_prompt_draft primary key (agent_id),
  constraint fk_agent_prompt_draft_agent foreign key (agent_id)
    references agent_worktree (id) on delete cascade
);

create table agent_prompt_attachment (
  id         varchar(255) not null,
  agent_id   uuid         not null,
  mime_type  varchar(255) not null,
  label      varchar(255) not null,
  source     varchar(255) not null,
  bytes      bytea        not null,
  created_at timestamp(6) not null,
  constraint pk_agent_prompt_attachment primary key (id),
  constraint fk_agent_prompt_attachment_agent foreign key (agent_id)
    references agent_worktree (id) on delete cascade
);

create index ix_agent_prompt_attachment_agent on agent_prompt_attachment (agent_id);
