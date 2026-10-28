-- WHAT A WAIT WAITS ON, AND WHAT AN AGENT NEEDS (epic qits-1681).
--
-- A declared wait held its subject only inside its EventSelection JSON. An agent's wait now names
-- it in columns of its own, parsed from the selection when the wait is declared (WaitSubjects) and
-- kept current from the events the wait listens to. The selection and the matching are untouched.
-- Every column is nullable; a desk wait leaves them all null.
--
--   subject_kind          RELEASE_REQUEST, CI_RUN, WORK_ITEM, COMMENTS or OTHER
--   subject_ref           the value the selection names, as written (a uuid or a qualified id)
--   subject_id            the subject's uuid, once resolved
--   subject_qualified_id  qits-x-rr-12, or qits-1681 (null for a run)
--   target_states         a JSON array of the state values the selection waits for; [] for none
--   subject_state         the subject's last known state (request state, run status, item status)
--   subject_state_at      when that state was seen to begin
--   subject_expected_by   runs only: when the run should have finished
--   subject_resolved_at   the last successful REST resolution
--   subject_error         the last resolution failure, for the CLI; never shown as attention
alter table agent_wait add column subject_kind text;
alter table agent_wait add column subject_ref text;
alter table agent_wait add column subject_id text;
alter table agent_wait add column subject_qualified_id text;
alter table agent_wait add column target_states text;
alter table agent_wait add column subject_state text;
alter table agent_wait add column subject_state_at timestamp(6) with time zone;
alter table agent_wait add column subject_expected_by timestamp(6) with time zone;
alter table agent_wait add column subject_resolved_at timestamp(6) with time zone;
alter table agent_wait add column subject_error text;

-- On agent_worktree, what the attention evaluation reads and writes:
--
--   blocked_at         when the item's explicit block (source EXPLICIT or BOTH) began; backfilled
--                      with now() for the blocks standing today, whose start nobody recorded
--   desk_asked_at      when the agent last asked the desk (an @desk comment on its item)
--   attention_code     what the agent needs, or null; attention_reason is its one line,
--   attention_reason   attention_since when it began, and attention_wait_id the wait it is about
--   attention_since
--   attention_wait_id  (not a foreign key: a wait closes, and the code clears, on its own clock)
alter table agent_worktree add column blocked_at timestamp(6) with time zone;
alter table agent_worktree add column desk_asked_at timestamp(6) with time zone;
alter table agent_worktree add column attention_code text;
alter table agent_worktree add column attention_reason text;
alter table agent_worktree add column attention_since timestamp(6) with time zone;
alter table agent_worktree add column attention_wait_id uuid;

update agent_worktree set blocked_at = now()
  where entity_blocked is true and block_source in ('EXPLICIT', 'BOTH');

-- Columns on two entities that are already CausedRows: no ArchRulesTest decision.
