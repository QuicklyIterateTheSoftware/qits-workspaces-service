-- THE COMMISSIONED CLIENT COLUMNS GO (qits-1683).
--
-- V3 added commissioned_client_id and commissioned_client_secret: the idp client pair a workspace's
-- container was once commissioned with. qits-876 stopped writing them, and qits-1683 unmapped them in
-- 2026.1028.163502, so the instance running when this migration lands names neither column. That is
-- why the drop ships in a release of its own: run alongside the unmapping, it would have pulled the
-- columns from under the old instance still draining during the rolling deploy (the race qits-770
-- hit). Nothing is copied first — a container holds a workspace token (V13) or no credential.
ALTER TABLE workspace DROP COLUMN IF EXISTS commissioned_client_id;
ALTER TABLE workspace DROP COLUMN IF EXISTS commissioned_client_secret;
