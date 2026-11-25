-- THE ASSISTANT MESSAGES ALREADY COUNTED AS USAGE (qits-2675, epic qits-2521).
--
-- Every shipped transcript line of an assistant message carries its message.id and the same
-- message.usage, once per content block, and a daemon may re-ship lines the store already holds.
-- A message is counted into AgentUsageRecorded once per session, ever: the ingest and the boot
-- backfill both insert (session_id, message_id) with ON CONFLICT DO NOTHING and count only the rows
-- they actually inserted. agent_id is the transcript's agent, for reading; no foreign key, so a
-- removed agent's row never blocks anything.
--
-- No entity maps it (a native insert is its only writer), so there is no ArchRulesTest or
-- EntityExposureTest decision to make. Nothing prunes it, as nothing prunes the transcripts.
create table transcript_usage_message (
  session_id  text        not null,
  message_id  text        not null,
  agent_id    uuid        not null,
  recorded_at timestamptz not null,
  primary key (session_id, message_id)
);
