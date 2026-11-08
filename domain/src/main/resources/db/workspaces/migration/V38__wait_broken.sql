-- A WAIT THAT CAN NO LONGER BE SATISFIED IS CLOSED, AND EVERY WAIT ENDS (qits-1961).
--
-- BROKEN: a wait whose subject, looked up, is final for its kind and not a target, stuck
-- (REJECTED, CONFLICTED, a branch step FAILED or CONFLICTED) for 30 min, unknown to its provider
-- (404), or a branch the delivery ledger has not followed 30 min after the declaration. The agent
-- gets a turn saying why; broken_reason keeps that one line. V23 declared the state check inline,
-- so postgres named it agent_wait_state_check (<table>_<column>_check, as V35 relied on); it is
-- replaced by a named one that admits BROKEN.
--
-- The ceiling: a wait declared with no timeout expires 8 h after it was declared, and none may ask
-- for more than 24 h. expiry_is_ceiling says the expiry is the platform's, not a timeout the waiter
-- asked for, so the timed-out turn can say so. The OPEN rows standing at migration get a ceiling:
-- one with no expiry 8 h after its declaration, one expiring later than 24 h after it at 24 h — in
-- both cases at least an hour from now, so a long-standing wait is not timed out by the migration
-- itself before its holder could hear of it.
alter table agent_wait drop constraint agent_wait_state_check;
alter table agent_wait add constraint ck_agent_wait_state
  check (state in ('OPEN','MATCHED','TIMED_OUT','BROKEN','CANCELLED'));

alter table agent_wait add column broken_reason text;
alter table agent_wait add column expiry_is_ceiling boolean not null default false;

update agent_wait
   set expires_at = greatest(created_at + interval '8 hours', now() + interval '1 hour'),
       expiry_is_ceiling = true
 where state = 'OPEN' and expires_at is null;

update agent_wait
   set expires_at = greatest(created_at + interval '24 hours', now() + interval '1 hour'),
       expiry_is_ceiling = true
 where state = 'OPEN' and expires_at > created_at + interval '24 hours';
