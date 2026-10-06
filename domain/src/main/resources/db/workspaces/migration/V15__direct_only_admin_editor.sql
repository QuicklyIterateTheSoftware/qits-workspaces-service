-- THE DIRECT PATH IS ADMIN AND EDITOR ONLY (qits-780), as a rule the schema keeps.
--
-- Since qits-628 the direct path — a container on the platform host, through qits-containers — is
-- for the two postures that may not leave the host: an admin workspace holds the host's docker
-- socket, and the editor is the platform's one shared container. Every regular workspace runs on a
-- workspace runner (qits-774 writes it RUNNER from its first commit, and the router refuses a
-- regular row at every DIRECT branch). V12's ck_workspace_runner_posture already keeps the first
-- half — an admin or editor row is never RUNNER; this file keeps the other half, for ACTIVE rows.
--
-- ACTIVE only, because resolved rows keep their history: a regular workspace that ran DIRECT and was
-- integrated or abandoned stays exactly as it was, placement included. Nothing ever starts a
-- resolved row, so its placement is a record of where it ran, not a claim about where it runs.

-- 1. THE LEFTOVER MAIN WORKSPACES, resolved first.
--
-- These are the retired per-project editor's main workspaces: a regular row with no parent,
-- standing on its repository's default branch, written by WorkspaceService.createMainWorkspace — a
-- create with no production caller since the editor became one container, deleted in qits-780. No
-- other writer ever left `parent_id` empty on a regular row, so an empty parent is what names one.
--
-- WHATEVER ITS PLACEMENT. Such a row was written DIRECT (the entity's default), and the qits-776
-- direct-migration sweep may since have moved it to RUNNER — which is what happened on the live
-- estate to the one row this was written for (row 202, workspace `main`: RUNNER, on no runner,
-- STOPPED, no container anywhere; its DIRECT container and volume had been removed beforehand with
-- delete-container, which also gave back its commissioned client). So the statement does not filter
-- on placement: a main workspace is retired as such, wherever it was last placed. Any credential
-- such a row still names is no leak either — CommissionReconciler gives back every client and every
-- workspace token no ACTIVE row names — and a runner still holding one drops it at its next estate.
--
-- RESOLVED IN SQL, AND NOT THROUGH THE DISCARD DOOR, ON PURPOSE. A discard pushes a delete of the
-- workspace's branch to the git host, and this workspace's branch IS the default branch. An UPDATE
-- here never touches the git host, so the default branch survives — which is the whole point. (The
-- discard door no longer deletes a main workspace's branch either, from qits-780 on; this file does
-- not rely on that.) The in-process WorkspaceResolved observers do not run, since there is no
-- request here; whatever they would have dropped for the row stays behind, read by nothing, as it
-- does for any resolved row.
--
-- A regular row WITH a parent is real work on a branch of its own and is never touched here.
--
-- The ABANDONED history entry is written in the same statement, so the timeline says when and why
-- the row went, exactly as a discard's would. Its id is drawn from workspace_event_seq the way
-- Hibernate's own blocks are, so no id it hands out later can collide with one taken here.
with resolved as (
  update workspace
     set status         = 'ABANDONED',
         resolved_at    = now(),
         runtime_status = 'STOPPED',
         queued_at      = null,
         result         = 'Resolved by migration V15 (qits-780): a leftover main workspace of the'
                          || ' retired per-project editor. Its container was removed beforehand,'
                          || ' and its branch is kept on the git host.'
   where status = 'ACTIVE'
     and not admin
     and not editor
     and (parent_id is null or parent_id = '')
  returning id, branch, parent_id
)
insert into workspace_event (id, workspace_id_fk, type, branch, parent, note, at)
select nextval('workspace_event_seq'), id, 'ABANDONED', branch, parent_id,
       'Resolved by migration V15 (qits-780); the branch is kept.', now()
  from resolved;

-- 2. THE RULE. An ACTIVE row is on a runner, or it is an admin or editor row.
--
-- IF THIS MIGRATION FAILS HERE it found an ACTIVE regular DIRECT row that is not a leftover main
-- workspace — one with a parent, so real work on a branch of its own. That is intended: Flyway fails,
-- the deploy rolls back, and the row is for a person to settle, never for a migration to guess at.
--
--     select id, repository_id, workspace_id, branch, parent_id from workspace
--      where status = 'ACTIVE' and placement = 'DIRECT' and not admin and not editor;
--
-- Integrate or abandon it (or delete its container and abandon it, for a branch worth keeping), then
-- run it again. None existed when it was written.
alter table workspace add constraint ck_workspace_direct_only_admin_editor
  check (status <> 'ACTIVE' or placement = 'RUNNER' or admin or editor);
