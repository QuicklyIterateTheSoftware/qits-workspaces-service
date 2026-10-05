-- The credential a RUNNER-placed workspace's CURRENT CONTAINER holds toward the platform (epic
-- qits-625, qits-802): one opaque qits_tok_ of the `workspace` kind, minted by this service at
-- qits-idp, in place of the commissioned client pair V3 stores for a DIRECT row. A runner's node
-- reaches the platform through the edge only, and the edge spends the token on every hop — git,
-- maven, npm, the MCP servers, the qits CLI and the daemon's own socket.
--
-- Set only for placement = 'RUNNER', and never together with V3's pair: a RUNNER row is never
-- commissioned a client, and a DIRECT row is never minted a token. Nullable, no backfill and no
-- default, for V3's reason: a workspace with no container holds no credential.
--
--   commissioned_token_id       qits-idp's id for the token: what it is deleted by, and what the
--                               commission reconcile compares a listed token with.
--   commissioned_token_subject  the `sub` the edge puts on the JWT it mints for the token
--                               (`tok-workspace-…`): what the daemon socket binds a caller to.
--   commissioned_token          the VALUE, stored so that the spec can be reproduced. The
--                               orchestrator a runner drives has no start verb either: a stopped
--                               container is started by presenting its spec again, and qits-idp
--                               hands a value out once. Its lifetime is the container's.
--
-- All three are deleted together with the token, in the statement that deletes it: at
-- delete-container, recreate, discard and integrate, and when the row is abandoned. A stop does not
-- delete it — a start re-presents the same spec.
--
-- Not a foreign key, for V3's reason: the token row lives in qits-idp's store.
alter table workspace add column commissioned_token_id varchar(64);
alter table workspace add column commissioned_token_subject varchar(255);
alter table workspace add column commissioned_token text;
