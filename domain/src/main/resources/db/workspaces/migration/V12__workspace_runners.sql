-- The workspace runners, and where a workspace's container runs (epic qits-624, qits-846).
--
-- A WORKSPACE RUNNER is a machine an operator installed with one pasted line, the way a qits-ci
-- runner is installed. It holds one socket to this service through the edge, takes queued
-- workspaces when it has a free slot, and runs their containers on its own node. This file holds
-- both halves of that: the runner rows, and the placement columns on `workspace`. They land in one
-- migration so the schema arrives once, before any code reads either half.
--
-- WHAT A RUNNER ROW IS. One runner an operator declared: a name, how many workspace containers it
-- may run at once, and where it stands in its registration. Shaped on qits-ci's `ci_runner` (its
-- V23), on purpose, because the install line, the register door and the commissioning are the
-- same exchange. Which of two nullable pairs is set is the whole registration state:
--
--   registration_token_id / _subject   set at create (and at every rotation). The register door
--                                      compares the bearer's `sub` to the subject. The token VALUE
--                                      is never stored: qits-idp keeps a hash, this table nothing.
--   client_id / registered_at          set once, by the register door. A row with a client is
--                                      registered, and a second register is a 409.
--
-- SLOTS 0 IS A STATE, NOT AN ERROR. A runner with no slots takes no workspace: it is how an operator
-- drains one without deleting it. Default 1. Slots count RUNNING containers, not owned rows: a
-- stopped workspace keeps its runner and frees its slot.
--
-- CAPABILITIES ARE JSONB: what the runner last said about itself (version, arch, docker, its
-- `dotClaudeVolume`, `login{claude,kimi,checkedAt}`). Each report merges its keys into the stored
-- object, so the last known value of a key survives a report that does not mention it, and survives
-- the runner going offline. jsonb rather than text because placement and the runners page read into
-- it. Null until the runner registers.
--
-- THE QUARANTINE has no exemption. A runner is quarantined from the moment it registers until its
-- first health check passes; an admin may lift it. last_health_check_* records the newest check.
-- Connection state is not here: it lives in memory, in the socket registry.
--
-- causation_id: the row is a CausedRow, as every entity here must decide (ArchRulesTest). A runner
-- is created by an operator's request, so the REST filter's restored scope stands when it is.
create table workspace_runner (
  id                         uuid                        not null,
  name                       varchar(64)                 not null,
  description                varchar(1024),
  slots                      integer                     not null default 1,
  capabilities               jsonb,
  registration_token_id      varchar(255),
  registration_token_subject varchar(255),
  client_id                  varchar(255),
  quarantined_at             timestamp(6) with time zone,
  quarantine_reason          text,
  last_health_check_at       timestamp(6) with time zone,
  last_health_check_ok       boolean,
  registered_at              timestamp(6) with time zone,
  last_seen_at               timestamp(6) with time zone,
  created_at                 timestamp(6) with time zone not null,
  causation_id               uuid,
  constraint pk_workspace_runner primary key (id),
  constraint uq_workspace_runner_name unique (name),
  constraint ck_workspace_runner_slots check (slots >= 0)
);

-- PLACEMENT: where this workspace's container runs. DIRECT is the platform host through
-- qits-containers, which is every workspace that exists today. RUNNER is a workspace runner's node.
-- It is decided at create and never changed, for the reason `admin` (V4) and `editor` (V7) are
-- columns: the container is started by presenting its spec again, so the answer has to be the
-- row's. NOT NULL DEFAULT 'DIRECT', so every existing row, and every row a caller writes without
-- saying, stays exactly where it runs today.
alter table workspace add column placement varchar(16) not null default 'DIRECT';
alter table workspace add constraint ck_workspace_placement
  check (placement in ('DIRECT','RUNNER'));

-- runner_id: the runner that holds this workspace. A workspace is sticky to its runner, because its
-- /workspace volume is node-local. Null on a RUNNER row no runner has taken yet, and on every
-- DIRECT row.
--
-- NO FOREIGN KEY, deliberately, and for ci_run.runner_id's reason: a runner is not deleted while it
-- owns an ACTIVE workspace (the control refuses with 409), and a resolved workspace keeps the id as
-- history. A key would either refuse the delete for history or cascade the history away.
alter table workspace add column runner_id uuid;

-- queued_at: when this RUNNER row was last asked to start, so the runners can take the oldest
-- first. Null while the row is not waiting.
alter table workspace add column queued_at timestamp(6) with time zone;

-- Only a RUNNER row names a runner. A DIRECT row with a runner would be a container two places
-- claim to run.
alter table workspace add constraint ck_workspace_runner_placement
  check (placement = 'RUNNER' or runner_id is null);

-- Admin and editor workspaces always stay DIRECT (owner decision, 2026-10-01). An admin workspace
-- holds the host's docker socket, and the editor is the platform's one shared container; neither
-- may run on a node the platform does not own. The control refuses this first; this is the rule
-- as the schema keeps it.
alter table workspace add constraint ck_workspace_runner_posture
  check (placement = 'DIRECT' or (admin = false and editor = false));

-- runtime_status gains QUEUED: a RUNNER row waiting for a slot. It is persisted, because the
-- runner's reserve reads it. UNAVAILABLE (the owning runner is offline beyond the reconnect grace)
-- is computed on every read and NEVER stored, so it is not admitted here.
--
-- V1 declared the check inline, so postgres named it workspace_runtime_status_check. It is
-- replaced rather than edited (V1 is applied), and the new one is named.
alter table workspace drop constraint workspace_runtime_status_check;
alter table workspace add constraint ck_workspace_runtime_status
  check (runtime_status in ('RUNNING','STOPPED','PROVISIONING','FAILED','QUEUED'));

-- The reads a runner's session makes: how many live rows it holds (its slot count), which ACTIVE
-- rows it owns (its estate, and the delete refusal), and what is queued for it. Every one is over
-- ACTIVE rows, so the index is partial, and DIRECT rows have a null runner_id.
create index ix_workspace_runner
  on workspace (runner_id, runtime_status)
  where status = 'ACTIVE';
