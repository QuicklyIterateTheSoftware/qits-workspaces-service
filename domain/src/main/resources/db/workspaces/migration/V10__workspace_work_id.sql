-- One id that binds a workspace to its work item, whatever the item's archetype (qits-112).
--
-- ticket_id/epic_id (V5) name only two archetypes, and entity_id (V8) is the qualified id
-- (e.g. qits-614), which changes when a project slug changes or an item moves project. work_id is
-- the item's entity id in qits-projects: a UUID, the same kind of value ticket_id/epic_id hold, and
-- it never changes. Lookups by work item use it; entity_id stays for display and links.
--
-- Backfill: every row that names a ticket or an epic takes that id. A row naming both (never
-- written in practice) takes the ticket, as the dispatch does.
--
-- Nullable and no constraint: a hand-made workspace names no work item. "At most one ACTIVE
-- workspace per work item" is kept by the dispatch (it answers the ACTIVE one first), not by an
-- index, so that existing duplicates cannot fail this migration.
alter table workspace add column work_id text;

update workspace set work_id = coalesce(ticket_id, epic_id) where work_id is null;

create index ix_workspace_work_id on workspace (work_id);
create index ix_workspace_entity_id on workspace (entity_id);
