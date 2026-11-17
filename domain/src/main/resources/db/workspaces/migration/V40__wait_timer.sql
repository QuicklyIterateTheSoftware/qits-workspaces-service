-- A WAIT MAY BE A TIMER (qits-2309).
--
-- Agents faked a timer with a selection nothing ever matched, so the ceiling or their own timeout
-- woke them. A wait declared with a timeout and no selection is a timer now: it names no event,
-- so selection and event_name are null, its subject_kind is TIMER, and the sweep closes it
-- TIMED_OUT when its time passes, with a turn of its own. It never matches an event, is never
-- looked up and never breaks.
--
-- No backfill: every row standing at migration names a selection. ix_agent_wait_open_event keeps
-- its definition; a null event_name is never asked for. Columns on agent_wait, an entity that is
-- already a CausedRow: no ArchRulesTest decision.
alter table agent_wait alter column selection drop not null;
alter table agent_wait alter column event_name drop not null;
