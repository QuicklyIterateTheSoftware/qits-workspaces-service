-- A TARGET WORKSPACE REPORTS ITS LEASE'S OOM KILLS (qits-1818).
--
-- qits-system's lease now counts the processes of its container the OOM killer took (cgroup
-- memory.events oom_kill), and names which killer acted: LEASE (the lease hit its own memory
-- limit) or NODE (the node ran out and the killer picked a process in this lease). Carried on
-- TargetLeaseChanged and the lease listing; TargetLeaseStates stores the row's own reading,
-- never lowering it, and clears both when the row is handed a new lease (WorkspaceRepository's
-- recordLease).
--
-- Both null for a DIRECT row, and for a TARGET row whose lease has reported no kill yet.
alter table workspace add column oom_kills bigint;
alter table workspace add column oom_scope varchar(8);
