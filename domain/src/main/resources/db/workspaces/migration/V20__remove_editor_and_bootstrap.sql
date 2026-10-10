-- THE EDITOR AND THE BOOTSTRAP CHAIN ARE GONE (qits-1152, D11 and D19; the editor is rethought in
-- epic qits-1150).
--
-- The editor's one ACTIVE row is resolved here, as V15 resolved the per-project editors' rows: no
-- code reads it any more, so it would otherwise stay ACTIVE for good. Its container
-- (qits-ws-editor-editor) and volume (qits_workspace_editor) are not this migration's to remove;
-- an operator removes them, and the commission reconcile gives its credential back.
--
-- The `editor` column itself stays, unread: the drain keeps old columns (D20), and the two checks
-- that named it are rewritten to name the admin posture alone.
with resolved as (
  update workspace
     set status         = 'ABANDONED',
         resolved_at    = now(),
         runtime_status = 'STOPPED',
         queued_at      = null,
         result         = 'Resolved by migration V20 (qits-1152): the shared editor workspace was'
                          || ' removed.'
   where status = 'ACTIVE'
     and editor
  returning id
)
insert into workspace_event (id, workspace_id_fk, type, branch, parent, note, at)
select nextval('workspace_event_seq'), id, 'ABANDONED', null, null,
       'Resolved by migration V20 (qits-1152): the editor was removed.', now()
  from resolved;

drop index uq_workspace_active_editor;

alter table workspace drop constraint ck_workspace_runner_posture;
alter table workspace add constraint ck_workspace_runner_posture
  check (placement = 'DIRECT' or admin = false);

alter table workspace drop constraint ck_workspace_direct_only_admin_editor;
alter table workspace add constraint ck_workspace_direct_only_admin
  check (status <> 'ACTIVE' or placement = 'RUNNER' or admin);

-- The bootstrap chain's run record: no writer and no reader left.
drop table workspace_bootstrap_run;
