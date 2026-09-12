-- What a workspace's container may push: the Git refs its commissioned credential states to qits-idp
-- (principal-bound-git-refs-plan.md, contracts C4 and C5).
--
-- `git_refs` is a JSON array of strings. Each entry is an exact ref (`refs/heads/ticket/x`) or a
-- prefix pattern ending in `/*`. It is written once at creation — the list the dispatch sent, or
-- `["refs/heads/<branch>"]` when it sent none — and after that only NARROWS: when another workspace
-- in the same project is created on a branch this list names, that ref leaves the list.
--
-- Null is every row that predates this file. Such a row is commissioned with the default list, its
-- own branch, which is what a new row without a stated list stores. So null and the default mean
-- the same thing, and nothing is backfilled.
--
-- `git_refs_pending` is true while the list on the row is narrower than the list qits-idp holds for
-- the row's live commission — a narrowing whose `PUT /idp/api/clients/{clientId}/git-refs` has not
-- landed yet. The commission reconcile retries it. A new commission states the current list, so it
-- clears the flag.
--
-- text, not text[]: the list is read and written whole, never queried by entry in SQL.
alter table workspace add column git_refs text;
alter table workspace add column git_refs_pending boolean not null default false;
