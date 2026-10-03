-- AT MOST ONE ACTIVE WORKSPACE PER WORK ITEM (qits-112), as a rule the database keeps.
--
-- V10 left this to the dispatch, which answers the work item's ACTIVE workspace before it makes
-- one. That holds for one caller at a time, not for two dispatches that both find nothing and
-- both write. This index settles that race the way uq_workspace_active_branch (V1) and
-- uq_workspace_active_editor (V7) do: the second insert fails.
--
-- Over work_id alone, not (repository_id, work_id): a work item names one target repository, so a
-- second ACTIVE workspace for it in any repository is a second agent on the same work.
--
-- The predicate carries the rest of the rule. status = 'ACTIVE' lets resolved rows (INTEGRATED,
-- ABANDONED; workspaces are soft-deleted) stay while a new one is made. Rows with a null work_id
-- (the editor, main workspaces) are not constrained.
--
-- IF THIS MIGRATION FAILS HERE it found real duplicates:
--
--     select work_id, count(*) from workspace
--      where status = 'ACTIVE' and work_id is not null
--      group by work_id having count(*) > 1;
--
-- Integrate or abandon the extra workspaces, then run it again. None existed when it was written.
create unique index uq_workspace_active_work
  on workspace (work_id)
  where status = 'ACTIVE' and work_id is not null;
