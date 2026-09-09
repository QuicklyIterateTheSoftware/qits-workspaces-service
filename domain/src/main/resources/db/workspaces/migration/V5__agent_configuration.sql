-- The AGENT CONFIGURATION DOCUMENT a workspace's CURRENT CONTAINER was born with: the resolved
-- per-surface configuration qits-projects answers at `GET /projects/api/agent-configuration`, fetched
-- once when the container is provisioned and handed to it as it starts.
--
-- IT IS A COLUMN FOR THE REASON THE COMMISSIONED PAIR IS ONE (V3). The orchestrator has no start
-- verb: a stopped container is started by presenting its spec AGAIN, under `Recreate.ifChanged`, and
-- a spec whose environment differs from the running container's is a spec change that REPLACES the
-- container. The document rides the environment, so a document re-fetched at every ensure would
-- replace a workspace's container every time the store was edited — or every time at all, since the
-- document carries its own `generatedAt`. Storing what the container was created with is what makes
-- the spec reproducible, and it is also exactly the epic's rule: a running container keeps the
-- configuration it was born with, and an edit reaches the NEXT container.
--
-- Both columns are the CONTAINER's, not the row's: every provision overwrites the pair, so a
-- recreate is how an edit takes effect and nothing has to remember to invalidate anything.
--
-- text, unbounded, no constraint: this service does not own the document's shape. It fetches it,
-- checks it is a well-formed document with at least one surface in it, and hands the bytes on
-- untouched — qits-projects owns the vocabulary and the shared harness library validates it at boot.
alter table workspace add column agent_configuration text;

-- WHY THE FAILURE IS A COLUMN AND NOT ONLY A LOG LINE. A container that cannot get its document is
-- created WITHOUT one and runs on the harness library's shipped defaults: refusing to create the
-- workspace would trade a configuration outage for a work outage, which is the worse of the two. But
-- a silent fallback nobody can see is how "green while dead" happens, so the fallback is recorded
-- where the container's other state is recorded and answered on the workspace's read model beside
-- `runtime_error`. Null means the container holds the document it asked for; a value means it is
-- running on the shipped defaults, and says why.
alter table workspace add column agent_configuration_error text;
