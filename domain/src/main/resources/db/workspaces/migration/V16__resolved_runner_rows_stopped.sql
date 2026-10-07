-- A RESOLVED RUNNER ROW READS STOPPED (qits-1064).
--
-- Until this release a RUNNER row's resolution (WorkspaceService.discardOnRunner — every integrate,
-- abandon and release-time resolution) wrote the status, resolved_at and queued_at, and left
-- runtime_status as it stood: RUNNING, mostly. The runner is told to delete the container and
-- answers with Deleted, but RunnerClaims only ever writes an ACTIVE row it owns, so that answer
-- never reached the column either. Every RUNNER workspace resolved before this file therefore still
-- claims a running container, and the history read and anyone counting "running" rows take it at its
-- word. (A runner's slot count reads ACTIVE rows only, so no slot was ever held by one of these.)
--
-- The code now writes STOPPED in the resolving transaction, as the branch-gone abandon
-- (abandonRunnerRow) always did; this statement brings the rows already resolved into line.
--
-- RESOLVED ONLY. WorkspaceStatus is ACTIVE | INTEGRATED | ABANDONED, so the two named below are
-- every resolved status there is. An ACTIVE row is the runner's to report and is never touched.
--
-- RUNNER ONLY. A resolved DIRECT row's runtime_status is not what this ticket is about, and its
-- container lived on the platform host, where nothing here can say whether one is still there.
update workspace
   set runtime_status = 'STOPPED'
 where status in ('INTEGRATED', 'ABANDONED')
   and placement = 'RUNNER'
   and runtime_status <> 'STOPPED';
