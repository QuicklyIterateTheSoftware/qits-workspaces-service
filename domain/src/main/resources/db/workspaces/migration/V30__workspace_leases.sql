-- WORKSPACES RUN AS WORKSPACE LEASES ON EXECUTION TARGETS (epic qits-1481, qits-1532).
--
-- A regular workspace's container no longer runs on a workspace runner this service registered and
-- talked to over its own socket: it runs in a WORKSPACE slot of an execution target, under a lease
-- qits-system grants. The runner registry goes in this same file, and a row records the lease it
-- runs under instead of the runner that held it.
--
-- 1. legacy_placement, FIRST: every row a runner held, copied before workspace.runner_id and
--    workspace_runner go. The row's first lease adopts the runner's container and volume, named
--    qits-workspaces-runner-ws-<rowId>-<oldRunnerId8>, and is pinned to the target on the same
--    docker engine. The adoption reads this table and deletes the entry once that lease is RUNNING.
--
--    engine_id and hostname are read from the runner's capabilities under the keys a target's
--    runner reports them by (engineId, hostname). qits-workspaces-runner-daemon never reported
--    either — its capabilities held version, docker, arch and os (hello), dotClaudeVolume
--    (inventory), login (login state) and health (the host's record of its newest check) — so both
--    are null on every row this copies, and the runner's own name is copied beside them as the last
--    way to find the target on the same node.
--
--    No foreign key: the row id names a workspace row, and the runner it names is dropped below.
--    Not a CausedRow (ArchRulesTest): the migration writes every row.
create table legacy_placement (
  row_id        bigint not null,
  old_runner_id uuid   not null,
  engine_id     text,
  hostname      text,
  runner_name   text,
  constraint pk_legacy_placement primary key (row_id)
);

insert into legacy_placement (row_id, old_runner_id, engine_id, hostname, runner_name)
select w.id,
       w.runner_id,
       r.capabilities ->> 'engineId',
       r.capabilities ->> 'hostname',
       r.name
  from workspace w
  left join workspace_runner r on r.id = w.runner_id
 where w.runner_id is not null;

-- 2. The lease a TARGET row runs under, and the target it was granted on. Both null while the row
--    has no live lease (lease_id is cleared once the lease is ENDED) and on every DIRECT row.
--    commissioned_token_* (V13) stays, and now holds the current lease's token. No foreign key:
--    both name rows in qits-system's database.
alter table workspace add column lease_id uuid;
alter table workspace add column target_id uuid;

-- adopted_volume: the retired runner's volume this row's work was adopted from. Every lease of the
-- row names it in adopt{container, volume}, because the runner mounts an adopted volume only when the
-- lease says so. Cleared with the volume by a delete-container.
alter table workspace add column adopted_volume text;

-- 3. RUNNER becomes TARGET. The three checks that spell RUNNER go first, or the update below would
--    break them: ck_workspace_placement (V12), ck_workspace_runner_placement (V12: only a RUNNER
--    row names a runner) and ck_workspace_direct_only_admin (V20).
alter table workspace drop constraint ck_workspace_placement;
alter table workspace drop constraint ck_workspace_runner_placement;
alter table workspace drop constraint ck_workspace_direct_only_admin;

update workspace set placement = 'TARGET' where placement = 'RUNNER';

alter table workspace add constraint ck_workspace_placement
  check (placement in ('DIRECT','TARGET'));
-- Only a TARGET row runs under a lease.
alter table workspace add constraint ck_workspace_lease_placement
  check (placement = 'TARGET' or (lease_id is null and target_id is null));
alter table workspace add constraint ck_workspace_direct_only_admin
  check (status <> 'ACTIVE' or placement = 'TARGET' or admin);
-- ck_workspace_runner_posture (V20: placement = 'DIRECT' or admin = false) names no RUNNER and still
-- holds as it is: an admin row is never TARGET.

-- 4. A row that was waiting for a runner's slot, or that a runner had taken and not yet reported
--    up, waits for nothing now: no runner will take it and it holds no lease. STOPPED, so its next
--    start asks for one. A RUNNING row keeps its status: its container still runs on the retired
--    runner's node, its daemon dials home through the edge as before, and its next start adopts it.
update workspace
   set runtime_status = 'STOPPED', queued_at = null
 where placement = 'TARGET' and runtime_status in ('QUEUED','PROVISIONING');

-- 5. The runner registry goes.
drop index ix_workspace_runner;
alter table workspace drop column runner_id;
drop table workspace_runner;

-- The lease reconcile and the event listener read rows by their lease.
create index ix_workspace_lease
  on workspace (lease_id)
  where lease_id is not null;
