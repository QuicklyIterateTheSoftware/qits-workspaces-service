-- Whether this row IS the web editor — the one shared editor container the whole platform opens,
-- rather than one editor per project.
--
-- WHAT THIS REPLACES IS A DERIVATION, NOT A COLUMN. Until now the editor was whichever workspace
-- rode a PROJECT-archetype repository's main branch: no row said so, the answer was computed from
-- the registry's archetype plus the row's branch, and the reasoning for having no column was that a
-- derivation cannot go stale. That reasoning was sound for a per-project editor and does not survive
-- the editor becoming one container for the platform: there is no project to derive it from any
-- more, no repository whose archetype could answer, and no branch that means anything. What is left
-- is a decision somebody made once — this row is the editor — which is exactly the shape of a
-- column.
--
-- It is a column and not a workspace_id convention for the reason `admin` (V4) is one: the
-- orchestrator has no start verb, so a stopped container is started by presenting its spec AGAIN,
-- and a spec that differs from the running container's is a spec change that REPLACES the container.
-- The editor flag picks the IMAGE and two environment variables, so an answer that moved between two
-- ensures would destroy the editor's container and its writable layer. A column on the row is what
-- makes it reproducible at every ensure, off one local read that cannot blink the way a registry
-- call can.
--
-- NOT NULL DEFAULT false: every row that exists today, and every row any ordinary create writes, is
-- an ordinary workspace. There is exactly one writer — WorkspaceService.createEditorWorkspace — and
-- no verb that promotes an existing workspace to the editor, the same posture `admin` has and for a
-- weaker version of the same reason: a workspace that became the editor would swap its image
-- underneath somebody's checkout.
alter table workspace add column editor boolean not null default false;

-- AT MOST ONE ACTIVE EDITOR ROW, and this is the rule as written rather than as agreed.
--
-- The editor is a singleton by definition now — one container, one volume, one deterministic name
-- (`qits-ws-editor-editor` / `qits_workspace_editor`) — so two ACTIVE editor rows would be two rows
-- claiming one container: two commissioned credentials fighting over the same spec, and an ensure
-- that replaces the other row's container every time it runs. The creator is find-or-write and two
-- callers racing it would both find nothing, exactly as two callers racing `createMainWorkspace`
-- would; this index is what settles that race structurally instead of by a lock, which is the
-- arrangement `uq_workspace_active_branch` already established in V1.
--
-- A PARTIAL index, and the predicate carries both halves of the rule. `editor` narrows it to the
-- editor rows — an ordinary workspace is not constrained by it in any way — and `status = 'ACTIVE'`
-- is what lets a resolved editor row linger (workspaces are soft-deleted) while a new one is made:
-- resolving frees the singleton exactly as it frees a branch. The indexed column is `editor`
-- itself, which is `true` for every row the predicate admits, so uniqueness over it means "at most
-- one".
--
-- IF THIS MIGRATION FAILS HERE it found real duplicates rather than a mistake in itself — which on
-- any database that has run a release before this one is impossible, since nothing could have set
-- the column the statement above just added.
create unique index uq_workspace_active_editor
  on workspace (editor)
  where editor and status = 'ACTIVE';
