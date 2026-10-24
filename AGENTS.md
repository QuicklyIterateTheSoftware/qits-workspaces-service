# qits-workspaces-service — working notes

Read `README.md` first: it defines the boundary (host side vs. workspace-daemon) and lists the
ports. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone builds against the platform Maven repository** — no monorepo and no prior `mvn install`.
`qits-eventstream:1.0.0` is resolved from local qits-artifacts; `qits-local-up.sh` publishes it before
this repository enters the pipeline.

That is why: the poms duplicate versions instead of inheriting them, the daemon protocol is
vendored, tests build their own git origins (`TestOrigin`) instead of using fixtures, and the
container runtime is faked in tests rather than reaching a real one.

**No submodules and no web client** (qits-1245). The qits-workspaces-frontend SPA that sat at
`service/src/main/webui/` is gone with Quinoa; the workspace pages are qits-landing-app's. A plain
clone is a complete checkout, and no build needs node. qits-eventstream is a normal Maven dependency
and must not return as a gitlink or reactor module.

**`service/` compiles to a GraalVM native image**, the rule qits-workspace-daemon and qits-gateway
already carry. `.sdkmanrc` names `25.0.2-graalce`, so `sdk env` is the whole toolchain and
`./mvnw package -Dnative` produces `service/target/qits-workspaces` in about a minute with no
container involved.

Two consequences, and this repo learned both the expensive way — every one of them shipped green
through `mvn verify` and was only found by running the binary:

- **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image ...
  Attempting to fall back to container build` and shells docker with a 1.8 GB Mandrel image. Green
  either way, so the fallback is easy to be in without noticing — recognise it by the image pull,
  and grep the log for that line rather than trusting the exit code.
- **The suite cannot see a native-image defect, by construction.** Native-image resolves everything
  at build time, so reflection, dynamic proxies, `ServiceLoader` and resources loaded by computed
  name have to be registered — and on the JVM none of that is needed, so the tests pass regardless.
  Three real ones landed here at once: a datasource url the suite overrides (`AUTO_SERVER`, see
  `domain`'s mp-config), two Jackson payloads reached only through a bare `ObjectMapper`
  (`WorkspaceMetadata`, `CaptureResource`'s records), and a raw Vert.x route reading a build-time
  config key that does not exist at runtime in a binary (`CaptureCorsRoute`, below).
  `NativeImageContractTest` in each module pins what a JVM run can still hold; the rest is only
  provable by booting the artifact.

**Build-time config keys are not readable at runtime in a native image.** Any `quarkus.*` key that
Quarkus fixes at augmentation is absent from the binary's runtime config, so
`@ConfigProperty(name = "quarkus.something")` silently takes its `defaultValue` there and works fine
on the JVM fast-jar, where `application.properties` is just another runtime config source. If
application code needs such a value, spell it as an application-owned key and derive the Quarkus one
from it — `qits.rest.path` / `quarkus.rest.path=${qits.rest.path}` is the worked example. A system
property still reaches a binary's runtime config, which is the only reason `ContainerProxyRoute` may
keep reading `quarkus.http.root-path`.

## Package and module conventions

`eu.wohlben.qits.workspaces.*`, split across maven modules with disjoint sub-packages so there is no
split package:

- `gitmirror/` — `gitmirror`, and nothing else. **The git substrate**, and the one module here with
  no Quarkus in it at all: no CDI, no MicroProfile config, no Jackson. It owns the local mirror per
  repository, the worktrees a merge is built in, and the push primitives — and it takes its
  collaborators as constructor arguments, so its tests run offline against throwaway local bares
  with no container, no database and no augmentation. `domain` builds it from config in exactly one
  bean (`GitMirrorRegistry`) and calls it through a handful of records. The boundary is deliberate:
  the same machinery could move into a daemon of its own, or into qits-artifacts as in-process JGit,
  without the integrate flow noticing.
- `domain/` — `entity`, `persistence`, `dto`, `mapper`, `control`, `error`. Framework-free in the
  sense that matters: no JAX-RS, no websockets. Entities are Panache active-record with public
  fields; mappers are MapStruct `@Mapper(componentModel = "jakarta")`. It depends on
  qits-eventstream for the causation persistence trio and the `QitsEvent` vocabulary the lifecycle
  events implement, and nothing else out of that jar — see "The causation stamp on every push" for
  why the boundary reads *seams* rather than *the bus*.
- `service/` — `api` (JAX-RS + SSE, including the raw vertx routes), `daemonhost` (the control
  socket and registry), `bus` (the event-bus wiring: the lifecycle announcer and the native
  registration of what it publishes, the declared-wait listener, the push causation stamp),
  `wiring`, `security`.
- `workspaces-events/` — `events` and nothing else: the lifecycle vocabulary (qits-1236), plain
  records over qits-eventstream, artifact `qits-workspaces-events`. See "The lifecycle events".

**The module is back, and why that is not a contradiction.** There was a `workspaces-events/`
module holding `SCMRelease`; it went with the release door on 2026-09-03, because qits-projects
publishes that event now, and the rule then was "do not reintroduce the module for an event this
service does not publish". qits-1236 is the case that rule left open: this service publishes again
— the agent and workspace lifecycle — so its vocabulary has a home of its own, between
qits-eventstream and everything else, depending on nothing but that jar. A consumer depends on it
and gets records, not this service's domain. It is still never a home for an event somebody else
publishes.

`control/` is flat on purpose, and deliberately stayed flat as the context grew: the monorepo split
it across `domain.repository`, `domain.workspace`, `domain.service`, `domain.bootstrap`,
`domain.process` and `domain.capture` to break cycles that do not exist here. Six aggregates, one
package per layer.

## Adding a dependency on another context

Don't. Declare a port in `domain/…/control/`, inject it as `Instance<T>`, and make absent a
supported configuration with a documented behaviour — see the table in the README. `RepositoryLookup`
is the sole mandatory one, because a workspace genuinely cannot exist without a repository.

Never add a JPA relation to another context's entity. Workspaces reference repositories by string
id; the two are in different databases and a foreign key cannot span them.

## Schema changes

`domain/src/main/resources/db/workspaces/migration/`, hand-written, its own lineage on its own
datasource. Never touch the monorepo's `db/migration` — that is a different database.

**The lineage is one `V1`, and it starts again there.** The four H2 files (`V1` workspace +
workspace_event, `V2` the four host tables, `V3` one active workspace per branch, `V4`
service_event.workspace_row_id) were deleted rather than continued when the store moved to
PostgreSQL: the move is an unwrap and a re-bootstrap, so no postgres database ever ran them and no
`V5__move_to_postgres.sql` had a reader. `V1`'s header records what the translation changed and what
it deliberately kept. **From here the ordinary rule is back**: extend, never renumber, and never edit
an applied file's body — Flyway checksums it.

**`V2__causation.sql` is that rule being followed**, and the first file to follow it: `causation_id`
(qits-eventstream's generic `CausedRow` column) on `workspace` and `workspace_event` — nullable, no
backfill, part of no constraint and never a foreign key, because the event it names lives in
qits-events' store. The decisions behind it are enforced by `ArchRulesTest` in `domain`: every
`@Entity` here implements `CausedRow` or declares `@Uncaused` with its reason in the javadoc, and a
new entity that skips the decision fails the build naming the class. Three in, three out:

- **`Workspace`** — in. Its creators run on the request thread or behind the agent scheduler; the
  `CausationStamp` listener reads whatever scope stands, and a slot written by a scheduler pass
  records none.
- **`WorkspaceEvent`** — in, and it is the one that carries the trace. The workspace row says why
  the unit of work exists; a MERGED/INTEGRATED entry answers to whatever asked for *that* landing.
  All six `recordEvent` sites are on the flow's own thread, including the ones a machine caller
  drives with a bearer of its own.
- **`AgentWorktree`** (qits-1152) — in. The dispatch door and a person's door write it on the
  request thread; the stamp says why the agent exists, and later updates do not move it.
- **`AgentBranch`** (qits-1152) — out: a native upsert on the daemon registry's thread, where no
  scope stands, recording the agent's own push.
- **`WorkspacePromptDraft`** — out, twice: the row is only ever written by the native `insert … on
  conflict` upsert, which no `@PrePersist` can see, and it is an updatable singleton besides.
- **`WorkspacePromptAttachment`** — out, following the draft it is the payload of: composition
  state, written one browser paste at a time, deleted wholesale with the draft.

The lesson to carry to the next entity: `CausationScope` is a plain ThreadLocal, so a row written
behind an executor or queue hop has no ambient scope and the stamp records null. Where the cause
exists as data at such a write site, set `causationId` explicitly — qits-ci paid for that one live.

**`V3__commissioned_credential.sql` adds two nullable `text` columns to `workspace`** — the idp
client a container was commissioned with, and its secret. **No `ArchRulesTest` decision to make**:
they are columns on an entity that is already a `CausedRow`, not a new entity, and the rule is about
the entity. The reasoning that *is* worth knowing is in the file's header and in
`WorkspaceCredentials` — why the secret is stored at all, and why the columns are cleared in the same
breath as the revocation rather than after it.

**`V5__workspace_subject.sql` adds two more**, `ticket_id` and `epic_id` — what a *dispatched*
workspace is for, where qits-projects said so. Same shape and same non-decision: columns on an entity
that is already a `CausedRow`, nullable, no backfill, part of no constraint, and **not** foreign keys,
because both name rows in another context's database exactly as `repository_id` does. Two independent
columns rather than a `(kind, id)` pair: a workspace names at most one in practice and the schema does
not need to enforce that. See "Dispatching an agent onto a branch" for why it is a field at all.

**`V6__workspace_git_refs.sql` added `git_refs` and `git_refs_pending`** — what the container may
push, and whether a narrowing had still to reach qits-idp. **`V27__drop_workspace_git_refs.sql`
drops both** (qits-1385): the push scope is the agent's since qits-1152, and a workspace states
`gitRefs: []`. See "The Git refs a workspace may push".

**`V12__workspace_runners.sql` adds the `workspace_runner` table and the placement columns**
(epic qits-624). `WorkspaceRunner` is a new entity and takes the `ArchRulesTest` decision: **in**, a
`CausedRow`, because an operator's request creates it, as qits-ci's `ci_runner`. On `workspace`:
`placement` (`DIRECT` | `RUNNER`, default `DIRECT`), `runner_id` (no foreign key: a runner that owns
an ACTIVE row is not deleted, and a resolved row keeps the id as history) and `queued_at`. Two checks
hold the rules: only a RUNNER row names a runner, and an admin or editor row is never RUNNER. V1's
inline `runtime_status` check is replaced by `ck_workspace_runtime_status`, which admits `QUEUED`;
`UNAVAILABLE` is computed on read and the column refuses it.

**`V13__workspace_token.sql` adds `commissioned_token_id`, `_subject` and `commissioned_token`**
(qits-625, qits-802): a workspace `qits_tok_`, the counterpart of `V3`'s pair and never set beside
it. Columns on a `CausedRow` again; see "The credential a workspace container holds". **Its header
says "a DIRECT row is never minted a token", and since qits-1084 that sentence is wrong**: a DIRECT
admin or editor row on an edge plane holds one too. It is a SQL comment and not a constraint — no
check in any migration (`ck_workspace_runner_posture`, `ck_workspace_runner_placement`,
`ck_workspace_direct_only_admin_editor`) mentions the token columns — so nothing forbids it, no
migration was added, and the applied file is left as Flyway checksums it. Read this paragraph, not
that header.

**`V14__runner_workspace_memory_limits.sql` adds `workspace_memory_limit` and
`workspace_memory_swap_limit` to `workspace_runner`** (qits-951), qits-ci's `step_memory_limit`
pointed at workspaces: nullable docker sizes an operator sets per runner, null meaning the platform's
`qits.workspace.memory-limit`/`memory-swap-limit`. `WorkspaceRunners` validates them (a swap needs a
memory and is at least it, or `-1`); `RunnerReservations` reads the row at each take and
`RunnerWorkspaceSpecs.limitsFor` lays it over the defaults, a memory with no swap being a hard cap.
DIRECT workspaces never read them. Columns on a `CausedRow`, so no `ArchRulesTest` decision.

**`V16__resolved_runner_rows_stopped.sql` sets `runtime_status = 'STOPPED'` on resolved RUNNER
rows** (qits-1064): `discardOnRunner` never wrote it and the runner's `Deleted` never reaches a
resolved row, so they kept reading RUNNING. The code writes it now.

**`V17__pending_agent_launch.sql` adds `pending_agent_launch`** (qits-1064): the agent launch held for
a QUEUED runner workspace, keyed by the workspace id, with a `claimed_by`/`claimed_at` claim.
`PendingAgentLaunch` is **`@Uncaused`**, for the prompt draft's reasons — a native insert, and a row
rewritten by its claim. See "Dispatching an agent onto a branch".

**`V20__remove_editor_and_bootstrap.sql`** (qits-1152) resolves the editor's row, drops
`uq_workspace_active_editor`, rewrites `ck_workspace_runner_posture` and the V15 check (now
`ck_workspace_direct_only_admin`) to name the admin posture alone, and drops
`workspace_bootstrap_run`. **`V21__agent_worktrees.sql`** adds `agent_worktree` and `agent_branch` (see
"Agents and the workspaces they run in"). **`V22__agent_prompt_and_held_launch.sql`** drops
`pending_agent_launch` (the agent row's `pending_text` holds what is still to be said) and re-keys the
prompt draft and its images on the agent (`agent_prompt_draft`, `agent_prompt_attachment`), dropping
the old composition state rather than migrating it. V17 and V18 below describe columns and a table
no code reads any more. **`V23__agent_waits.sql`** (qits-1153) adds `agent_wait`, an agent's declared
waits; `AgentWait` is a `CausedRow` (the agent's own request writes it). See "Declared waits".
**`V24__yield_keeps_the_harness.sql`** (qits-1212) drops `uq_agent_worktree_running`: a yield no
longer stops the harness, so a workspace may hold several RUNNING agents.
**`V25__agent_start_refusal.sql`** (qits-1217) adds `start_refusal` and `start_refused_at` to
`agent_worktree`: why the daemon last refused to start the agent, and since when. Nullable columns on
a `CausedRow`, so no `ArchRulesTest` decision; see "Agents and the workspaces they run in", Drive.
**`V28__agent_nudge.sql`** (qits-1460) adds `nudged_at` (nullable) and `nudge_count` (`not null
default 0`) to `agent_worktree`: when the scheduler last nudged an idle agent, and how many nudges
in a row it has had. Columns on a `CausedRow`, so no `ArchRulesTest` decision; see "Agents and the
workspaces they run in", Nudge.
**`V29__agent_block_author.sql`** (qits-1511) adds `block_author` (nullable `text`) to
`agent_worktree`: the subject qits-projects names as having set the item's explicit block. An agent
whose own subject set an `EXPLICIT` or `BOTH` block is still nudged. A column on a `CausedRow`, so no
`ArchRulesTest` decision; see "Agents and the workspaces they run in", Nudge.
**`V30__workspace_leases.sql`** (epic qits-1481, qits-1532) retires the workspace runners: it first
copies every row with a `runner_id` into `legacy_placement(row_id, old_runner_id, engine_id,
hostname, runner_name)` — `engine_id` and `hostname` from the runner's `capabilities ->> 'engineId'`
/ `'hostname'`, which qits-workspaces-runner-daemon never reported, so both are null on every row
and the runner's name is copied as the last way to find its node — then adds `workspace.lease_id`,
`target_id` and `adopted_volume`, renames placement RUNNER to TARGET (`ck_workspace_placement`,
`ck_workspace_direct_only_admin` and the new `ck_workspace_lease_placement` rewritten around it),
stops the rows that were QUEUED or PROVISIONING for a runner, and drops `workspace.runner_id` and
`workspace_runner`. `LegacyPlacement` is **`@Uncaused`**: the migration writes every row. See
"Placement: DIRECT and TARGET".

**`V18__dispatch_command_id.sql` adds `dispatch_command_id` to `workspace`** (qits-895): the daemon
command the last launch this service made started, replaced by every accepted launch
(`DispatchService.recordLaunchedCommand`). A column on a `CausedRow`, so no `ArchRulesTest` decision.
**It is mapped `insertable = false, updatable = false` and written only by
`WorkspaceRepository.recordDispatchCommand`, a bulk update**: `Workspace` has no `@DynamicUpdate`,
so any other transaction's flush of a row it loaded before the launch committed would put the old
id back. See "The agent-activity rollup", the relay paragraph.

**The target is PostgreSQL 18.4** — the tag `components/qits-database/qits-database-oci` is built
from, and the version the suites' embedded binaries are, so a migration is proved against the engine it ships on.
Two H2 habits are gone with it: a rule that applies to some rows is a **partial unique index** now
(`create unique index … where …`, which is what `uq_workspace_active_branch` finally is, instead of
the generated column H2 forced), and `clob`/`blob` are `text`/`bytea`. The second is also an entity
rule — see below.

**`@Lob` is banned on this context's entities.** On H2 a `@Lob String` was a clob and the two agreed;
on postgres `@Lob` means a LARGE OBJECT, so Hibernate binds an oid and the insert fails against the
column the migration declares. Spell it `@Column(columnDefinition = "text")` — or `"bytea"` for
bytes — as `Workspace.preamble`, `WorkspacePromptDraft.content` and `WorkspacePromptAttachment.bytes` do.

**Native SQL is postgres SQL.** `WorkspacePromptDraftRepository.upsert` is the only place this repo
writes any, and it is `insert … on conflict (…) do update` — H2's `merge into … key (…)` until the
move.

Remember that workspace rows are **soft-deleted**. A child table in another context gets no cascade;
publish through `WorkspaceResolved` instead, and fire it synchronously inside the resolving
transaction so observers can join it.

## Surviving a postgres cutover

The platform restarts its own postgres, and this service has to still be there afterwards. Two
layers, and they are not interchangeable.

**Layer 1 is three config lines per postgresql datasource**, and all three are needed:
`jdbc.driver=eu.wohlben.qits.db.PatientPgDriver`, `jdbc.validate-on-borrow=true`,
`jdbc.acquisition-timeout=15S`. The driver (qits-db-core) delegates to pgjdbc and **holds** a
connection request while postgres is coming back — refused, unreachable and `57P03` only, so an auth
failure still fails at once. `validate-on-borrow` turns a dead pooled connection into a fresh
creation attempt, which is the thing the driver makes patient, and the acquisition timeout keeps the
pool's waiter alive while it works. Measured: Agroal does **not** spend its acquisition budget on a
failed connection *creation*, so the last two lines alone never held anything.

`DatasourceBaselineTest` (in `service`) fails the build when a datasource is missing a line. It runs
there and not in `domain` because **two** postgresql datasources reach the deployable — `workspaces`
from the domain jar and `eventstream` from the qits-eventstream jar — and only this module sees both.
The eventstream one's three lines currently sit in `service`'s `application.properties` as a
stand-in, because the released jar predates them; the comment there says when to delete them.

**Layer 2 is `DbRetry` at read seams, and it is placement-sensitive.** Layer 1 cannot help a request
whose connection died *after* statements ran; a plain `DbRetry.call` re-runs the block, which is
safe for a read and not for an arbitrary write, so it is explicit and rare. Today there is exactly
one: `ContainerProxyRoute.resolve`, the daemon lookup that
backs the proxy's 404s — the only path to a workspace-daemon's API, so a blip there takes the file
browser, the terminals and the coding-agent surface down and calls a live workspace missing.

Three rules govern where a wrap may go, and the first is why this one is at the caller rather than
inside `DaemonProxyTargets.resolve`:

- **Outside the transaction.** `resolve` is `@Transactional`; retrying inside it would re-run
  statements on a transaction already marked for rollback. The retry has to surround the
  transactional call so each attempt gets a new one.
- **Never inside a `synchronized` monitor.** Several `WorkspaceService` methods are synchronized —
  sleeping in one holds the lock for the whole deadline. Stay away from them.
- **Never split a deliberate multi-op bracket.** Wrap what runs *after* irreversible work, never a
  bare insert: a commit whose ack was lost would be duplicated.

`ContainerProxyDbPatienceTest` proves both halves — a severed connection is retried once and answers,
and a genuine absence still 404s on the **first** attempt, because an absent row is an answer rather
than a failure. Widen the retry to "anything that went wrong" and every unknown id would sit on the
deadline before 404ing.

**Layer 3 is `DbRetry.inNewTx` at write seams**, which is a different method for a real reason:
`call` retries a block, and when the block is a write, a connection that died inside the *commit*
round trip would be retried into a second write. `inNewTx` owns the transaction boundary, so it can
tell the two apart — it retries only a failure **thrown out of the body** that is connection-classed
(Quarkus rolls a failed body back and never commits it, so that position is known), and rethrows
everything the transaction manager reports, a `RollbackException` included. Narayana spells "the
commit failed, outcome unknown" and "rolled back before committing" with the same exception type, so
a rollback it claims is not evidence of anything.

Three things follow, and each is a way to get this wrong:

- **The wrapped method loses its `@Transactional`.** `inNewTx` opens the transaction per attempt;
  an annotation on top of it would only be the interceptor joining the one already open. The wrap
  goes at the public method and the retried unit is a private one.
- **The body is DATABASE-ONLY.** It re-runs, so anything in it that is not a row — a push, a
  container call, an HTTP call, an event — happens once per attempt. Every wrap here fires its
  `WorkspaceChangePublisher` hint *after* the unit, and hands the repository id out of it so the
  hint needs no second query.
- **The unit ends with a `flush()`, or the retry buys nothing.** Hibernate sends a `persist` at
  commit by default, which is precisely the phase `inNewTx` reports rather than repeats. Flushing
  last moves the write into the statement phase, where a lost connection is a certain no-commit.
  The draft's own writes need no flush: they are native `insert … on conflict` / `delete`
  statements, which execute where they stand.

**Three writes are wrapped, and they were chosen for what a lost one costs**, not for being writes:

- **`WorkspacePromptDraftService.saveDraft`** — the autosave, on a debounce while someone types. It
  is also the perfect first adopter: one `insert … on conflict`, idempotent by construction, which
  is why `WorkspacePromptDraft` is `@Uncaused` in the first place.
- **`WorkspacePromptDraftService.deleteDraft`** — delete-if-present, twice, in one transaction
  deliberately: a draft kept with its images gone is a broken composition.
- **`WorkspacePromptAttachmentService.addAttachment`** — a paste, whose bytes nothing else holds a
  copy of. The row id is minted **before** the retried unit so every attempt writes the same primary
  key; generated inside, a retry would be a second row.
**What is deliberately not wrapped**, beyond the read-seam rules above (which all still hold — stay
outside `WorkspaceService`'s `synchronized` methods, and never split a multi-op bracket):

- **The workflow verbs** — `createWorkspace`, `createSlot`, `mergeBranch`, `cleanupBranch`,
  `discardWorkspace`, and every agent scheduler pass. They orchestrate pushes and containers, so their bodies are not database-only
  and re-running one is not a re-run of a write.
- **The fail-soft diagnostics**: dropping one is the designed behaviour.
- **`updateAttachment` / `deleteAttachment`** — verbs on an image already on screen and already in
  the database. A failed one is the same click again, which is worth less than a wider wrapped set
  costs to review.

`PromptDraftWritePatienceTest` proves the pair, and it is shaped unlike the read test on purpose:
its wound fires *after* the real statement has run, because only that arrangement can tell a retry
that re-executed rolled-back work from one that added a second effect. `prompt_version` is the
witness — the upsert bumps it once per execution, so a save that survived one severed connection
reads **1**. Its second case is a SQLState `23505` violation surfacing on the **first** attempt,
which is the same narrowness assertion the read test makes about an absent row.

The measurements are in the superproject's `db-patience-plan.md`; the doctrine is step 9 of
`docs/project-setup-quinoa-angular.md`.

## What identifies a workspace

`Workspace.id` — the generated `Long`. It is what routes, the ports and every FK'd child table use,
and it needs no `repositoryId` beside it, because a unique id is already unique.

`workspaceId` (the string) is a **branch-derived label**, not an identifier: unique only per
repository, only among ACTIVE rows, and reusable once a workspace resolves. It stays the
path/container-name segment — `containerName(workspaceId, repoId)` and the on-disk workspaces dir
both keep it deliberately — and it is still guarded for uniqueness among ACTIVE rows so those paths
stay unambiguous. Do not reintroduce it as an identity: that is what let two ACTIVE workspaces own
one branch.

The **branch** is the resource a workspace claims. At most one ACTIVE workspace per
`(repositoryId, branch)`, enforced in `createWorkspace` and structurally by `UQ_workspace_active_branch`.

The **daemon control plane** is keyed on the id too: `DaemonControlSocket` is
`/workspaces/daemon/{id}` and `WorkspaceDaemonRegistry`'s maps are `Map<Long, …>`. A daemon
still announces its own label in its `Hello`, and the registry keeps it (`DaemonConnection.label`)
purely so events and log lines read readably.

The socket takes that id as a **`String` and parses it**: websockets-next rejects the endpoint at
build time — `@PathParam must be java.lang.String` — and the failure surfaces as an unloadable test
class, not as a compile error, so it is worth knowing before you type `Long`.

`LegacyDaemonControlSocket`, which served the old label path (`/api/workspace-daemon/{label}`) for
containers provisioned before the move, is **deleted** (qits-780): measured live, no daemon had
dialled it in seven days, and every container carries the id-addressed URL. Its label branch in
`DaemonAgentBindingCheck` went with it. A container that still dialled the label path would simply
have no control socket until recreated.

## Where this service answers

One surface. This service has a host of its own, `workspaces.<env>.<domain>`, but serves **no web
client** there any more (qits-1245): `/`, `/workspaces` and `/workspaces/` are plain 404s. Every route is
`/workspaces/<second level>/…`, always: the edge routes **verbatim by prefix**, so the service serves
the prefixed path itself, on its own host and on every other one; there is no unprefixed form, on the
edge or on `qits-net`. `README.md` has the table.

The one thing to know before you add a route: **`quarkus.rest.path` moves the JAX-RS routes and
nothing else.** A raw Vert.x route or a `@WebSocket` path registers straight onto the router with a
literal and must carry `/workspaces` itself. Four do, each for its own reason:

- `DaemonControlSocket` — `/workspaces/daemon/{id}`, and a **cross-repo contract**:
  `WorkspaceContainerFactory` injects `ws://<host>:<port>/workspaces/daemon/<id>` as
  `QITS_WORKSPACE_DAEMON_URL` and qits-workspace-daemon dials exactly that. Change both together.
  A commissioned daemon exchanges its own client pair for a platform-audience bearer and
  presents it on every upgrade; the endpoint requires `qits:system` or `qits:agent`. A caller with
  `qits:agent` and not `qits:system` may open only its own workspace's socket: `DaemonAgentBindingCheck`
  (an `HttpUpgradeCheck`, so the refusal is a real 403 before the upgrade) compares the token's `sub`
  with the row's `commissioned_client_id` — or,
  on a row that holds a workspace token, with its `commissioned_token_subject` instead (qits-812;
  a client id never matches there). Which one is decided by what the row HOLDS
  (`Workspace.holdsToken`/`boundSubject`), never by its placement: every TARGET row holds a token,
  and so does a DIRECT admin or editor row on an edge plane (qits-1084), whose daemon then dials
  `wss://workspaces.qits.<domain>/workspaces/daemon/<id>` through the edge rather than the
  `ws://` qits-net address. `DaemonSocketBearerLifetime` drops the bearer's expiry on this path for a
  `tok-` subject only, so a token daemon's socket outlives the edge's 300 s JWT and a pair daemon
  keeps its lifetime.
  `qits:system` is not bound until agents switch to their own role (phase 4 of the superproject's
  `principal-bound-git-refs-plan.md`). The local/no-IdP topology
  stays anonymous only while the machine-auth rollout gate is off. Do not make this path public to
  repair a failed dial-home: a missing bearer is an integration failure, not a routing exception.
- `ContainerProxyRoute` — `ContainerProxyPath.PREFIX`, `/workspaces/container/`, **the only path by
  which anything reaches a workspace-daemon's HTTP API.** `container` and not `daemon` because the
  control socket already owns that segment, and that literal is the one hardest to change (it is
  baked into every running container). Not a gateway route, and there must never be a `DAEMON`
  constant in the gateway's `QitsService`: a daemon is one process per container with no stable
  address to configure, and this service owns the row and the lifecycle.
  **It rewrites no path**: the daemon receives `/workspaces/container/{id}/files`, not `/files`, and
  is told that prefix is its own address via `QITS_WORKSPACE_DAEMON_API_BASE_PATH`
  (`ContainerProxyPath.base`, injected by `WorkspaceContainerFactory`). A hop that rewrites a path leaves
  the two ends disagreeing about the destination's address; do not add a `substring` here.
  **A WebSocket upgrade does not go through `vertx-http-proxy` at all** — `proxyUpgrade` does it by
  hand. Two reasons, both read out of 4.5.26. The library skips its whole interceptor chain on an
  upgrade, so the bearer never reached the daemon and both interactive sockets answered 401 (the
  same defect the gateway works around in `EdgeHeaders.applyToUpgrade`). And the pipe it then builds
  is bare `a.handler(b::write)` installs with **no `writeQueueFull`, no `pause`, no `drainHandler`**
  and a failure arm that prints `"Handle this case"` and a stack trace — so a chatty process on a
  terminal socket piles up in this process's heap. The hand-rolled path pauses and drains in both
  directions (the discipline `DaemonStreamRoute` already had one hop further in) and forwards a
  refused handshake with the daemon's own status instead of a bare 502. It pipes **raw bytes, never
  frames**: re-framing would break the terminal's close semantics.
  A stub origin that accepts any handshake shows neither defect, which is why
  `ContainerProxyRouteTest` rejects on a bad bearer and parks a reader mid-flood.
- `DaemonStreamRoute` — `WorkspaceTunnels.STREAM_PATH_PREFIX`, `/workspaces/daemon/stream/`, where a
  daemon's reverse-tunnel dial-back lands. It shares a prefix with `DaemonControlSocket` and does not
  collide (`{id}` matches one segment, so no daemon can be named `stream`), and it is a **raw** route
  rather than websockets-next for a hard reason: `io.quarkus.websockets.next.Connection` exposes
  `sendBinary` and no `writeQueueFull`/`drainHandler`, and a byte tunnel with no backpressure signal
  is an unbounded heap buffer. `request.toWebSocket()` gives a real `WriteStream`. A token row's
  dial-back — every TARGET row, and a DIRECT admin or editor row on an edge plane (qits-1084) —
  comes through the edge and must also carry a validated bearer whose `sub` is the row's token
  subject (`WorkspaceTunnels.DialBack.tokenBound`, captured when the tunnel opens); otherwise the
  same bare 404 an unknown nonce gets. A pair row's dial-back is the nonce alone, as before.
- `CaptureCorsRoute` — derives its path from the REST prefix instead of repeating it, because a
  preflight on a different path from the POST it clears is worth nothing, and the client reads a 404
  there as "hide the button" rather than as an error. It reads **`qits.rest.path`**, not
  `quarkus.rest.path`: the Quarkus key is build-time and resolves to the `@ConfigProperty`
  `defaultValue` in a native image, which put the preflight on `/capture` and left the real endpoint
  answering browsers with RESTEasy's bare 200 and no CORS headers — green suite, dead button.
  `application.properties` spells `qits.rest.path` once and derives `quarkus.rest.path` from it, so
  there is still no second value to drift. `RootPath` normalizes both that key and
  `quarkus.http.root-path`; use it rather than doing the string arithmetic by hand.

`/workspaces/q/*` (openapi, swagger-ui) sits outside `quarkus.rest.path` and moves only with
`quarkus.http.non-application-root-path`. `quarkus.swagger-ui.path` is relative and follows on its
own — do not pin it.

**There is no SPA fallback any more**, so nothing has to be kept off it: a path under
`/workspaces` that no route claims is a 404 on its own. The `quarkus.quinoa.ignored-path-prefixes`
key, and the `WebUiRedirect` that sent the bare `/workspaces` to the client, went with Quinoa
(qits-1245). `WebUiGoneTest` pins the 404s.

**A workspace is not a sub-resource of a repository.** This context holds a repository id as a
string, in another database, with no FK — so collections filter by `?repositoryId=` and an item is
`{id}` alone. `/branches/{merge,cleanup,resolution}` take `?repositoryId=` too: a branch has no id of its own,
so the repository narrows and the body names the branch. `history/{id}` carries no repository at
all; it was decoration on the item routes and a real filter only on the collection.

**The bootstrap chain is gone (qits-1152, D19)**: the host's runner, `workspace_bootstrap_run`
(`V20`), its `bootstrap-runs` read and the protocol's bootstrap frames. Agents set up what they need
themselves.

**Workspace services are gone, not moved (qits-947).** The dev servers a repository could declare
under `services:` — run by the daemon's `ServiceSupervisor`, projected here by a host
`ServiceSupervisor`, framed through `/workspaces/service/{row}/{svc}/**` and recorded in
`service_event` — were taken out of scope and removed end to end, with nothing replacing them:
`ServiceProxyRoute`, `/workspaces/api/service-events`, the `SERVICES`/`SERVICE_EVENTS` hint topics,
the `qits.services.*` keys, every `SERVICE*` container env var and the table (`V19`). Two
tolerances stay on purpose, for daemons built before the removal: `QitsConfig` ignores unknown properties, so
an old daemon's `services:` key does not degrade its config view to empty; and the sealed-protocol
switches (`WorkspaceDaemonRegistry.onMessage`, `WorkspaceTunnels.capabilityFor`) end in a `default`
arm instead of naming the service records and the `SERVICE` stream target, so this compiles against
the protocol jar both with and without them. Do not reintroduce a host-side service concept on the
strength of those remnants.

**A streamed start ends when its provision does** (`finishProvision`); the bootstrap phase that
used to follow it went with the chain.

Host-owned surfaces are plain JAX-RS under `qits.rest.path`:

- `GET /workspaces/api/workspaces/{id}` — the single-workspace read. The same `WorkspaceDto` the
  collection serves, so a detail page opens from a bare id; ACTIVE only, 404 for a resolved one
  (whose live half would be uniformly null — `history/{id}` is where that record stays readable).
- `GET`/`PUT`/`DELETE /workspaces/api/agents/{id}/prompt-draft` — the composition the agent's next
  turn is written in (keyed on the agent since qits-1152). Database rows, no container.
- `GET`/`POST`/`DELETE /workspaces/api/agents/{id}/prompt-attachments[/{attachmentId}]` — its
  images, on their own SSE topic so a debounced text autosave does not re-download every picture.

Resolving an id costs a query, which matters on the **SSE routes**: a `Multi`-returning method runs
on the IO thread, so a lookup in one throws `BlockingOperationNotAllowedException` (a 500) unless
the method is `@Blocking` — see `WorkspaceEventsController`. Ordinary JAX-RS methods are dispatched
to worker threads already and need nothing.

## The agent-activity rollup, and why `ENDED` is kept

`WorkspaceDaemonRegistry` caches each daemon's agent-activity reports per `commandId` and rolls them
up per workspace: **BUSY > WAITING > IDLE > ENDED**, else null. `WorkspaceDto.agentActivity` is that
rollup, and it is RUNNING-only and self-healing like `clean` — a disconnect drops the whole
workspace's entries and a reconnect re-reports them.

`ENDED` used to be **evicted on arrival**, which meant the host could never report it. That is not a
missing field, it is a deleted workspace: the agent-activity bar is ordered by when a workspace's
state last changed, so a session that has just stopped belongs at the front — it is the one waiting
for your next prompt — and eviction made it vanish at precisely that moment. It is kept now and
expires on `qits.workspace.agent-activity.ended-ttl-ms` (30 min: survives a reload and a coffee
break, does not still claim a slot hours later).

Two properties worth not simplifying away:

- **The TTL is evaluated on every read**, not only by the sweep, so the rollup is correct the instant
  it passes. `sweepEndedSessions` exists to *announce* the expiry — nothing on the detail page polls,
  so an `AGENT_ACTIVITY` hint is the only way a fade reaches a browser.
- **A live report always wins.** A resume overwrites the same `commandId`'s entry, so the TTL only
  ever governs a session that stayed finished.

`ENDED` is lowest precedence deliberately: a workspace with one finished session and one still idling
has a live conversation in it.

**A killed agent is an `ENDED` plus a `runtimeError`** (qits-951). The OOM killer takes the agent and
leaves the container — PID 1 survives, docker still says `running` — and a SIGKILLed agent fires no
hook, so the rollup used to keep the `IDLE` it last heard and the workspace read exactly like a turn
that finished. A daemon at capability 7 sends `ENDED` itself with `hookEvent` `OomKilled`/`Killed`;
`WorkspaceDaemonRegistry.onAgentKill` writes `AGENT_OOM_KILLED: …`/`AGENT_KILLED: …` onto the row
through `AgentKills` and takes it back off on the next `BUSY`. Every re-provision clears it as it
clears any `runtimeError`; a runner inventory deliberately does **not** (the container is running,
which is no news to an agent kill). Until the protocol pin carries `AgentActivity.message()` the
sentence is the host's own and names no memory cap.

**An agent's waiting state is relayed to qits-projects** (qits-895; per agent since qits-1152),
which derives a work item's BLOCKED from it. A daemon sets `AgentActivity.awaitingInput` (null =
unknown, never relayed) and, at capability 10, `agentId`. `AgentService.onActivity` stores the flip on
the agent row (`waiting_since`) and relays only a flip (and, since qits-1460, every frame names the
agent's open waits in `waitingOn` — see "Declared waits"), through the `AgentWaitingReporter` port
(`wiring/HttpAgentWaitingReporter` → `POST /projects/api/work/{workId}/agent-waiting`). A frame naming
no agent is a person's own session and relays nothing. The registry also keeps the last state per
agent (`AgentActivityView`), which the scheduler reads to say a turn at a turn boundary.
**Typing a turn does not clear `waiting_since`** (qits-1511): neither the drive nor a wait's wake
touches it, only the BUSY frame of the turn's `UserPromptSubmit` does — an ordinary flip, relayed
like any other, so the item's "waiting for you" clears when the agent starts working. The drive
used to clear it and relay `TurnDelivered` at once; a turn the harness swallowed (typed into a
permission dialog) then left an idle agent that read as busy, never nudged and never yielded. The
drive stamps `last_activity_at`, which restarts the nudge's and the yield's quiet clocks, so
neither acts in the seconds before the BUSY frame arrives.

## The protocol is a dependency, and its version is the workspace image

`workspace-daemon-protocol/` used to be a module here — a byte-identical copy of the daemon repo's,
same java package, different artifactId, because that module was published nowhere. **It is gone.**
qits-workspace-daemon publishes it as `eu.wohlben.qits:qits-workspace-daemon-protocol`, and this
reactor depends on it. Its own pom always said this would be the day: the package was kept identical
precisely so the swap would be a pom change with no source edits, and it was.

**The version of that jar IS the `qits/workspace` tag this service starts containers from.** One
artifact carries both, because the thing this service must *speak* and the thing it must *start* are
one release. `WorkspaceImage.VERSION` is the constant; `WorkspaceContainerFactory.imageVersion()`
reads it. (The editor image's pin went with the editor, qits-1152.)

**What that replaced, because the failure is the reason for all of it.** Both versions used to
arrive as `env.QITS_WORKSPACE_IMAGE_VERSION` / `env.QITS_EDITOR_IMAGE_VERSION`, qits-configuration
entries its release listener rewrote the moment an image was pushed. So a new daemon was used by the
next workspace with nothing having tested the pair, and the fallback defaults shipped in `domain`'s
properties file aged in silence until they named images the registry's retention had deleted — any
run *without* the injection started from a reference that could not be pulled. A dependency cannot
age that way: it has to **resolve** for this reactor to build.

Three consequences worth keeping straight:

- **A protocol change reaches this service as a version bump, gated here.** Add a message in the
  daemon repo, release it, let qits-platform-maintenance move the pom line, handle the new case in
  `WorkspaceDaemonRegistry.onMessage`. Slower than editing a vendored copy, and that is the point —
  the host that must understand a frame is the one whose gate now sees the change.
- **`WorkspaceDaemonPinIT` is what makes the pin mean something.** It downloads the daemon at exactly
  the pinned version, runs it as a process and round-trips the real protocol. It is named in
  `.config/qits/userflow-stories`, the one-class-per-line list the composed QA pipeline turns into
  `-Dit.test`; a test not named there never runs, so the two move together.
- **The two config keys survive as emergency overrides and must stay unset.** They are `Optional`
  with no shipped default, so absent is the ordinary state. Setting one pins an image nothing has
  tested against this service, which is the honest reading of an override.

**qits-projects-service still vendors a copy of the same module.** Until it takes the dependency too,
`diff -r` against the daemon repo is still the drift detector over there.

## The mirror, and what it replaced

Nothing here opens the shared volume of bare origins any more — there is no config key naming it and
a deployment does not mount it. Each repository is **mirrored** under
`qits.workspaces.data-dir` (`mirrors/<repoId>.git`, worktrees beside it), filled by `git clone
--mirror` and kept current by `git fetch --prune` — qits-ci's own cache pattern, one size larger
because this service has to merge and not only read config.

Three kinds of git call, and the distinction decides correctness:

- **Wire reads** (`ls-remote`) answer *does this branch exist* and *what sha does the host hold*.
  Authoritative, and deliberately not cached: `ensureContainer` **abandons a workspace** on that
  answer, and a mirror one fetch behind would report a live branch as gone. An unreachable host
  **throws** rather than answering "gone", because "I could not ask" and "it is not there" were one
  value while the origin was a directory and must not be over the wire.
- **Local reads** (`rev-list`, `merge-tree`, `merge-base`) need objects, so they run in the mirror
  and the caller refreshes first. `qits.workspace.git.mirror-freshness-ms` bounds how stale one may
  be; it is 5 s because the workspace listing computes ahead/behind per workspace and a browser
  polls it. Nothing that *decides* anything reads through the window — `canCleanupBranch` forces a
  refresh, and a refresh that fails leaves the counts UNKNOWN, which refuses.
- **Writes** are pushes. All of them. `createBranch` is `push <from>:refs/heads/<new>`, cleanup and
  discard are `push :refs/heads/<branch>`, and a merge is a worktree on the mirror plus
  `push HEAD:refs/heads/<target>`. There is no other door, which is the property the whole change
  exists to establish.

**No push option is left.** `-o qits.release` went with the release door — the git host's
protection hook guards the default branch alone, and nothing here writes one — and so did the push
option that used to quiet a release's trunk push and `RepoMirror.createBranch`'s own copy of it: CI
is gated only by release requests now, so there is nothing left for a push to suppress, and every
push this module makes, including a branch create, is an ordinary one. The mirror is a **cache**:
delete it and the next request re-clones.

## The release door left, and what stayed

**Until 2026-09-03 this service released.** `POST /workspaces/api/workspaces/{id}/release` and
`POST /workspaces/api/branches/{release,execute-release}` merged a branch into the repository's
default branch, stamped a CalVer `YYYY.MMDD.HHMMSS` version into the same index, committed both as
one `release(<version>): <summary>` commit, pushed it atomically with an annotated tag under
`-o qits.release`, published `SCMRelease`, and promoted the same sha onto an `environment/*` entry
branch. **All of it is gone**, along with `Mode.RELEASE`, `VersionStamp`, `VersionBumper`,
`MavenVersionBumper`, `NpmVersionBumper`, `PomVersions`, `PackageJsonVersions`, `TextSplice`,
`DeploymentSpecReader`, `ReleaseAnnouncer`, `SCMReleaseAnnouncer`, the `workspaces-events` module
that held `SCMRelease` (the name came back with qits-1236 for the lifecycle events, and holds
nothing of the release), and `qits.workspaces.release.entry-branch`.

**A release is a release request in qits-projects now**: it folds `main`, the named branches and
every released-but-unmerged tag onto a `release/<id>` branch through qits-githost's in-core git
primitives, the QA pipeline builds that fold, and a green gate stamps the manifests, tags the fold
and publishes `SCMRelease`. `main` is finalized after the deployment. The bump engine's logic was
ported to qits-projects (`PomVersions` there carries the note); the version-uniqueness guarantee is
now githost's refusal to overwrite a tag ref rather than an atomic branch+tag push.

**`/workspaces/{id}/integrate` and `/workspaces/{id}/merge` are gone (qits-1152, D21)**, and
`BranchIntegrator` with them: a workspace is a slot with no branch, and the "done" signal is the work
item's VERIFIED, which opens a release request in qits-projects for the agent's branches. What
stayed is the branch-keyed pair below and the `gitmirror` primitives.

**`POST /workspaces/api/branches/{merge,cleanup}`** are the branch-keyed pair, and both are a
person's door (`@RolesAllowed("qits:admin")` at class level, restated in each body — see
`BranchController`'s comment for the measurement behind the belt-and-braces). `merge` takes an
arbitrary target and answers with conflicts rather than throwing; `cleanup` removes a branch when
that loses no work.

**`POST /workspaces/api/branches/resolution?repositoryId=<id>` is the one door a release calls, and
it is not a release door.** Body `{branch, target?, commit?, result?}`, answering
`{resolved, workspaceId?}`; `{qits:admin, qits:system}` on `BranchResolutionController`, a class of
its own — `BranchController`'s class role is a person's, and a class-level `@RolesAllowed` is
enforced on ArC's internal calls too, so widening that class is the 403 of 2026-09-03 pointed the
other way. It exists because of an **absence**: qits-projects' Auto Release deletes each released
branch through a githost primitive that writes the ref in core and fires no event, so the workspace
standing on that branch stayed ACTIVE forever, holding a container, a volume and a commissioned
credential for a ref nobody can fetch (measured live 2026-09-05, the storage-creep wrapper
workspace). What arrives is a fact about a branch; what happens is the resolution this service
already performs whenever a branch stops existing. `target`/`commit` are the release's version and
sha carried onto the history event and read as nothing else — nothing here merges, stamps, tags,
pushes or announces, and the events module does not come back for it.

Four things about it are decided rather than incidental:

- **No workspace is the normal answer**, `resolved:false` and not a 404 — most released branches
  carry none — which is also what a second call answers, and the caller is best-effort and retries.
  That path asks qits-projects nothing at all: the common answer must not cost a round trip.
- **The main workspace is refused on BOTH belts** — `parent == null` on the row, and the branch
  equalling the repository's default branch — because they are independent readings (ours and
  qits-projects') and a main branch renamed between them leaves exactly one right. An ordinary
  discard still resolves a main workspace, but since qits-780 `doDiscard` keeps its branch on the
  same two belts.
- **`doDiscard` gained a `deleteBranch` flag rather than a copy.** The ref is already gone, so the
  push would be a round trip whose only outcome is the failure that method's catch swallows — a
  stated no-op instead of a silent one, the reading `ensureContainer`'s branch-gone abandon already
  makes. The flag and not a second sequence because the ORDER is the part worth having one of
  (`fireStopping` before `rm` is pinned by `WorkspaceContainerStoppingRecorder`).
- **An agent's leftover work is LOGGED, never refused.** This is the one place this service
  knowingly discards uncommitted work: there is nothing left to push to, and an immortal workspace on
  a deleted branch is worse. The probe is the agents' cleanup check (`AgentWorktreeGuard`, qits-1263),
  and an agent it cannot check counts as holding work. It is deliberately **not** routed through `cleanupBranch`/`canCleanupBranch`/
  `sweepMergedBranches`: those decide whether a branch *may* be deleted and fail closed on a ref they
  cannot resolve, and here the deletion already happened.

**Every ref this service moves is moved by a push, and that is the point.** The bare origins used to
be on our own disk, on the volume the git host serves, so a branch could be created, merged or
deleted by writing the ref — which is exactly what this service did, and it is why **no branch
creation, no merge and no cleanup it ever performed produced a CI run**: a filesystem ref update
fires no `post-receive`. Pushing over HTTP through qits-githost makes receive-pack the sole writer
of every ref. The address is `qits.githost.url` behind the `GitHostAddress` port.

Three properties, each of which is why a step is where it is:

- **`git worktree add --detach` on the MIRROR is what makes "no partial state" true.** The merge and
  the commit happen against a `HEAD` that is not a branch, in a repository nobody serves, so a
  conflict or a crash leaves the target branch **byte-identical**. A failed run needs no unwind —
  only a worktree removal, which `MirrorWorktree` does on close. The orphaned commit is git's to
  collect.
- **`git merge --no-ff --no-commit` is what makes the landing one commit.** `MERGE_HEAD` stays set
  and the index stays staged; the single `git commit` that follows is a two-parent merge. No amend,
  no second commit. (It is also what let the release's bump write into the same index, back when
  there was one.)
- **The push is the compare-and-swap**, with no option and none needed: an ordinary push is
  fast-forward-only, which is receive-pack's property rather than an option's. A target that moved
  is `NOT_FAST_FORWARD` — nothing landed, try again. The repository lease turns the common case from
  "one fails" into "one waits".

**The 409s carry a `reason`, and it is additive.** The envelope is still `{"message": …}`; an
`IntegrateConflictException` adds `reason` ∈ `CONFLICT` / `MERGE_CONFLICT` / `NOT_FAST_FORWARD` /
`ALREADY_INTEGRATED` / `PUSH_REJECTED` / `RELEASE_REQUIRED`, plus `conflicts` (the conflicted paths)
for the two conflict modes. `WorkspacesExceptionMapper` is where that happens and it is the only type
it special-cases. `PUSH_REJECTED` is the git host's protection hook refusing: **that must surface as
a 4xx carrying the hook's own message, never a 500**, because the message is the only thing on screen
that says what to do instead — and it is **not retryable**, which is how the client treats it, so
never reuse the value for a race. `RELEASE_REQUIRED` is the wrong-door refusal and the only one where
nothing was attempted. The set also **shrinks when a flow leaves**: `VERSION_ALREADY_RELEASED` and
`HEAD_MOVED` went with the release door and nothing here can produce either.

The enum reaches `docs/openapi.yml` through `api/ApiError`, a schema-only record declared on the
`@APIResponse`s and returned by nothing — the mapper still builds the body, because the extra fields
are present only when they apply and a record would write them as explicit nulls.

**`branches/merge` 409s with `RELEASE_REQUIRED` when the target resolves to the default branch.** It
keeps every other target, answers a conflict rather than throwing, and needs no workspace.

One consequence, recorded because it is a real loss rather than an oversight: **a plain branch can no
longer be auto-cleaned up after integration.** A plain branch's cleanup parent is the main branch by
definition (`canCleanupBranch`), so it is eligible only once merged *into* that branch — and nothing
here merges into that branch.

**Three inherited sharp edges this flow had to fix rather than inherit**, and two of them were
platform-wide:

- **Stale worktrees were never pruned** anywhere in this service. A crashed merge left its admin
  registered and the *next* one failed with "already checked out" — a failure outliving the process
  that caused it. `RepoMirror.worktree` prunes before adding, and removes a surviving directory the
  prune cannot (prune drops the registration, not the files).
- **`.tmp-merge-<System.currentTimeMillis()>` collides** within a millisecond. The worktree is named
  after the **slugged source branch**, unique per repository by construction and the reason the flow
  is keyed by branch rather than by a workspace row.
- **`GitExecutor` had no timeout at all.** Every git call that talks to the git host — the mirror's
  clone and fetch, `ls-remote`, and every push — now carries one
  (`qits.workspace.git.network-timeout-ms`, which widened and replaced
  `qits.workspace.integrate.push-timeout-ms` when the push stopped being the only network call).
  The bound covers the whole call, not just `waitFor` — a transport that connects and then says
  nothing blocks in `readLine()`, so the drain runs on its own thread and `destroyForcibly` is what
  unblocks it. Local git **inside the mirror** keeps its unbounded wait, where a bound would only
  turn slow into broken. The machinery lives in `gitmirror`'s `GitCli`; `GitExecutor` delegates to
  it rather than carrying a second copy.

**`gitmirror` still carries more than the flow above uses** — `tag`, `--atomic`, `withOption` — and
that is deliberate: the module is the git substrate, its primitives are git's rather than a flow's,
and `RepoMirrorTest` proves them offline. Do not read an unused primitive there as a leftover of the
release door.

**`TestOrigin` sets `receive.advertisePushOptions`**, and it is load-bearing rather than tidy. JGit
advertises push options in production; a local `receive-pack` does **not** by default, and `git push
--push-option` fails outright against a server that did not advertise them. Nothing this service
pushes carries one today, and the line stays because the fixture must be able to refuse the exact
argv that ships rather than a laxer one. `FakeGitHostAddress` points the mirror at the local bare and
replaces the **transport only** — the clone, the fetch, the `ls-remote`, the push, the ref
negotiation and the fast-forward check are all real.

**`GitHostAddress` has two methods returning one string, and the split is why.** `fetchUrl` is asked
by every read; `pushUrl` is asked once, immediately before a push. `FakeGitHostAddress.beforeNextPush`
hangs its staged second writer on the second of those, so it fires at the one instant a race is about
rather than on the mirror's first fetch. A deployment returns the same value from both
(`ConfiguredGitHostAddress` literally does). Staged rather than raced, because a real race is
nondeterministic about which side loses.

## The causation stamp on every push

The git host publishes an SCM event per ref a push moves — `SCMPublishCommit`, `SCMPublishTag`,
`SCMDeleteBranch`, `SCMDeleteTag` — and it publishes them **under whatever `X-Qits-Causation-Id` the
push carried**, read off the receive-pack request. Every ref this service moves is moved by a push,
so this hop is where a chain would otherwise break: an ask → push → commit event → CI run is one
chain only because the producer says what it was doing.

`domain/…/control/PushCausation` is the port and `service/…/bus/EventstreamPushCausation` reads
`CausationScope` — the port-in-`domain`, implementation-in-`service` split the `SCMRelease`
announcer used to share, and the only one left: `domain` stays free of the bus's **seams**. `GitMirrorRegistry` injects it as
`Instance<T>` and hands `GitMirrors` a `Supplier<String>`; `RepoMirror.push(cwd, spec)` turns the
answer into `-c http.extraHeader=X-Qits-Causation-Id: <uuid>`.

**The word is SEAMS, not "the bus", and the narrowing was deliberate (2026-08-10).** The
eventstream jar also carries the platform's causation *persistence vocabulary* — `CausedRow`,
`CausationStamp`, `@Uncaused`, three jakarta-persistence-shaped types with no publish, no subscribe
and no wire in them — and `Workspace` and `WorkspaceEvent` implement it, so the jar sits in
`domain/`'s pom now. What the rule still forbids is control flow: no listener, no publisher, no
`EventFrame`, no `QitsEventBus` anywhere in `domain`, and the announcement ports stay ports —
`LifecycleAnnouncer` (qits-1236) takes a built `QitsEvent` record and is implemented in `service`. The
`PushCausation` port above is untouched by the narrowing — reading the ambient scope to build a
header is a bus seam, and it stays in `service`. What the dependency costs is honest and paid in
the suite: the jar's persistence unit boots in this module's tests too, so
`testdb/EmbeddedPgConfigSource` feeds it a database of its own and the test properties keep the bus
dark — the same consumer contract `service` has always honoured.

Four things are deliberate:

- **It is attached in `push(Path, PushSpec)` and nowhere else.** Both `push` overloads, both
  worktree pushes, `createBranch` and `deleteBranch` all funnel through it, so no call site has to
  remember — and there is no external remote in this service that could pick it up by accident.
- **The header name is a literal in `gitmirror`**, because that module has no Quarkus in it and
  stays that way. `EventstreamPushCausationTest` asserts it equals `CausationHeader.NAME`; nothing
  else connects the two strings.
- **A value that will not parse as a UUID is dropped rather than interpolated.** A cause is
  advisory and must never fail a push, and a newline in an HTTP header would be injection. The
  parse is the check and the sanitiser at once.
- **Absent is a supported configuration.** No implementation, no cause, no header — which is exactly
  how every push behaved before the git host published anything.

Nothing in the suites can assert the header *arriving*: the fixtures are local bares and
`http.extraHeader` is inert over a file transport. `CausedPushWiringTest` asserts the part that is
this repo's — that a push made inside a scope, through the injected registry, still lands — and
qits-githost owns the far end.

## The SCMRelease event, which is not published here any more

**qits-projects publishes it.** `SCMRelease {projectId, repository, repositoryName, branch,
version}` says source control has this release and nothing more — the statement that an artifact
exists is qits-ci's `SoftwareRelease`, a whole build later. This service published it from the
instant a release push was accepted until 2026-09-03; the publisher moved with the release door, and
`ReleaseAnnouncer`, `SCMReleaseAnnouncer`, the `workspaces-events` module that held the record and
`bus/EventWireReflection` all went with it (the last two are back since qits-1236, for the lifecycle
events, and hold no `SCMRelease`). **The wire name is the class name**
(`QitsEvent.signature()` returns the simple class name), so qits-projects' own identical record is
the same event on the wire; nothing keying on the signature noticed the move.

What the file `.config/qits/ci-event-release.yml` in this repository does is unchanged: it selects
`SCMRelease` for **this** repository and builds the released tag. The publisher is somebody else's
now, the consumer is still ours.

**What else of the bus is here**: the causation half (above) — `EventstreamPushCausation` reading
the ambient scope onto every push, and the eventstream jar's persistence trio on the entities; one
listener, `bus/AgentWaitListener` (qits-1153, "Declared waits"); and since qits-1236 one publisher,
the lifecycle events ("The lifecycle events"). `qits.eventstream.enabled` stays off in
`%dev`/`%test`: a clone-alone suite has no qits-events to reach, and the tests assert the events at
the `LifecycleAnnouncer` port instead.

Two things the move left behind here, both worth not re-deriving:

- **`RepositoryLookup.RepositoryView` carries `name` and `projectId`** because `SCMRelease` needed a
  coordinate a committed CI selection could address — a row id is per-platform (a self-seeded
  repository's is a UUID) and `repository: { exact: … }` on an id matched nothing, silently. The
  fields stayed for the workspace daemon, which clones by the public `(project, name)` pair. Both
  are nullable.
- **A `@DefaultBean` on a wiring implementation is load-bearing** wherever the suite has a double:
  two unqualified beans of one type fail the build at `ArcProcessor#validate`, for every test at
  once. `HttpRepositoryLookup` carries it; `SCMReleaseAnnouncer` did.

## Authentication

Authentication happens at `qits-gateway`. This service resolves a principal from a trusted header
(`X-Qits-User`, read by `workspaces/security/ForwardAuthMechanism`) and authenticates nothing.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select in this service. The shared `qits-auth-core` resolves both
`X-Qits-User` and `X-Qits-Roles`; human-facing REST boundaries use Jakarta
`@RolesAllowed("qits:admin")`. Machine-facing boundaries require an authenticated identity and
retain their narrower `MachineAuth` audience/scope checks.

**Every read also takes `qits:agent`; no write does** (phase 4 of the superproject's
`principal-bound-git-refs-plan.md`: agents keep every read and lose only writes) — with two stated
exceptions until qits-1481 (a workspace runner's health check, gone with the runner), and an agent's own declared waits
(qits-1153, below). It is stated on
each read METHOD — every `GET`, the three event streams — and never on a class, so a write added to
a class later does not inherit the agent role. A method-level list replaces the class's, so each one
repeats the class's roles beside `qits:agent`. `AgentReadAccessTest` has one test per class. The
raw Vert.x proxy routes carry no role list here and are not part of this rule.
**The daemon proxy is bound, not open to every agent** (qits-1386): `/workspaces/container/{id}/*`
opens a shell and writes files, so `ContainerProxyRoute` checks the caller itself.
`quarkus.http.auth.permission.container-proxy` keeps anonymous out (upgrades included; the path is
relative so it follows `quarkus.http.root-path`). `qits:system`, `qits:admin` and `qits:admin-agent`
reach any workspace; `qits:agent` only one whose `boundSubject` is its `sub`, or that holds a live
agent whose token subject it is; anything else is 403. `ContainerProxyAuthorizationTest` pins it.
**The one agent write door: declared waits** (qits-1153, W4). `qits:agent` and `qits:admin-agent` may
create, list and cancel waits on `/workspaces/api/agents/{agentId}/waits`, but only for the agent
their token belongs to: the principal must equal the agent row's `commissioned_token_subject`.
`qits:admin` and `qits:system` act on any agent. `AgentWaitAccessTest` pins it.
**`qits:desk` is admitted on one door, bound to its project** (qits-1493). `POST
/workspaces/api/agent-dispatches/delivery` alone admits it, beside the usual machine set. A
`qits:desk` caller holding none of `qits:admin`/`qits:admin-agent`/`qits:system` may deliver only to
the item's ACTIVE agent's own project: the door resolves that agent through
`AgentService.projectOfActiveAgent` and compares it against the token's `project` claim
(`MachineIdentity.claimMatches`, `QitsClaims.PROJECT` — the structured claim's enforcement-side
vocabulary, next to `CredentialCommissioner.PROJECT_CLAIM` on the minting side); a mismatch is 403
"The delivery desk may prompt only the agents of its own project" and nothing is said. An item with
no ACTIVE agent is not a binding violation — the door still answers `agentId: null`, as it does for
every other role. `qits:admin`, `qits:admin-agent` and `qits:system` stay unbound.
**`qits:admin-agent` is admitted wherever `qits:admin` is** (qits-628 follow-up, owner's request
2026-10-07). It is the role qits-idp issues, beside `qits:agent`, to an ADMIN workspace's container
credential (context kind `workspace-admin`), whose agent holds the host's docker socket. It is named
explicitly right after `qits:admin` at every check — every `@RolesAllowed` list,
`BranchController.requireAdmin` —
and never implied by code, so a door that must stay human-only can drop it on its own. A new door
that admits `qits:admin` lists it too. `AdminAgentAccessTest` pins it, door by door, against
`qits:agent` alone.
**`X-Qits-*` is the gateway's reserved namespace, stripped from every inbound request
unconditionally**, so a client cannot forge one. That strip rule is the entire reason the header can
be trusted here — and it is why `ForwardAuthTest` sets the real header rather than reaching for
`@TestSecurity`. The header *is* the contract under test; a test that mocked the identity instead
would pass just as happily against a mechanism that never reads it.

The daemon control socket is the exception that proves the rule. `/workspaces/daemon/{id}` is
token-free by necessity — its callers are daemons inside containers, holding no user token — and it
names its caller with a path parameter, so anything on `qits-net` can claim to be any workspace's
daemon (`migration-plan.md` §9 item 22). Edge auth neither touches nor fixes that.

## The workspace image, and who pulls it

`qits.workspace.image` is a **registry-qualified reference at a pinned calver**, and there is no
`defaultValue` behind it in code — a deployment that loses the key must fail at startup naming it
rather than launch something a host happens to have lying around. The properties file carries the
reasoning; here is what surrounds it.

**There is no hand-built image any more, and no recipe for one.** `qits/workspace:latest` was a
local tag on one machine: no version, no registry, no pipeline, and a platform unwrap swept it,
after which every workspace launch died with "no workspace-daemon dialed home". What publishes the
image is **qits-workspace-daemon's own release pipeline**, which builds two docker artifacts —
`qits/workspace-daemon`, the small one qits-deployments reads, and `qits/workspace`, the released
toolchain with the daemon copied in as its entrypoint. **This pin names the second.** Only that one
is startable as a workspace, and the difference is one hyphen.

**`.config/qits/ci-event-upstream-workspace-daemon.yml` is what moves the pin**, on
**`SoftwareRelease`** — the artifact-published event, not `SCMRelease`, which fires at tag time and
would pin a version whose 3.4 GB image is still uploading or never uploads at all. It selects on
`packageName` rather than on the repository, because `SoftwareRelease` carries a repository ROW ID
and this daemon's is a per-platform UUID; the file says so at length, along with why it probes the
registry's `manifests/<tag>` and never `tags/list`.

**qits-containers pulls it, not this service.** The pin travels in the ensure request's spec —
`containershost/WorkspaceContainers.ensureRequest` — under **`PullPolicy.MISSING`**, which is the
same inspect-then-pull rule the deleted `DockerExecutor.ensureImage` followed, enforced one hop out
where the daemon actually is. A present image is never re-checked against the registry: the
reference is a version, so what is local under that name *is* the release. Three things follow:

- **The bound is the client's, and it is not a key of this repo's.** `qits.workspace.image-pull-timeout-ms`
  is gone with `qits.workspace.container-runtime`; the ceiling is
  `qits.containers.client.ensure-timeout` (PT10M, shipped in the client jar and twenty times the
  other deadline for exactly this reason — the base alone is ~3.4 GB). Setting either deleted key
  sets a key nothing reads.
- **A wrong pin comes back as a refusal, and the launch fails naming it.** The orchestrator answers
  409 `IMAGE_MISSING` (`ContainersAnswer.imageMissing()`), which `WorkspaceContainers.holdThrough`
  deliberately does **not** hold through — only 401/403 and unreachable are about the moment, and no
  retry helps until something pushes the image. That failure is *expected*: the tag is written by a
  release train and the image is published by another repository's pipeline, so the two can
  disagree. What it must never do is hang or fall back to something stale.
- **`WorkspaceContainersTest` is what pins both halves** — the literal spec including
  `PullPolicy.MISSING`, and an `IMAGE_MISSING` refusal surfacing in the thrown message. It runs
  against a stub HTTP origin; no docker and no orchestrator involved.

**Measured 2026-08-10, and worth knowing before you debug a launch:** the platform registry holds no
tag under `qits/workspace`, none under `qits/workspace-daemon` and none under the toolchain base
`qits/workspace-base`. The shipped pin is therefore a **placeholder** naming nothing, and the first
real value will arrive through the train (or by hand at the first published calver). Until then a
workspace launch fails at the orchestrator's pull, which is the loud version of what used to be
silent.

## The web editor: removed (qits-1152)

The shared editor workspace — its door (`POST /workspaces/api/editor/ensure`), its proxy route on the
`editor.*` vhost, its keepalive and idle-stop switch, its `EditorState` frame and
`StreamTarget.EDITOR` tunnel target, its image pin (`qits-workspace-editor-image`) and the
`qits.editor.*` keys — went with epic qits-1152 (D11) and is rethought in epic qits-1150. `V20`
resolved its row; the `editor` column stays, unread.

## Agents and the workspaces they run in (epic qits-1152)

A **workspace** is a slot: one container under a WORKSPACE lease on an execution target (an admin one DIRECT on the platform host),
scoped to a project's wrapper, holding one long-lived clone. An **agent** is one work item's coding
agent and its worktree inside a workspace (`/workspace/agents/<agentId>/<wrapper name>` on the
daemon). A workspace hosts many agents, and each keeps its harness running while it is placed. The decisions are D1–D26 of the
superproject's `agent-workspace-decoupling-plan.md`; `AgentService` holds the semantics.

**The rows.** `agent_worktree` (`V21`, `AgentWorktree`, a `CausedRow`): work item, wrapper branch,
slot, `state` ACTIVE → OBSOLETE → REMOVED, `run_state` QUEUED → RUNNING ⇄ YIELDED, session, the turn
still to be said (`pending_text`), the agent's own token and Git refs. `agent_branch` (`@Uncaused`, a
native upsert on the registry's thread) holds every (repository, branch) the daemon reported pushed:
what the agent's views list, never what a release's resolution looks up.
A unique index keeps one ACTIVE agent per work item. A workspace may hold several RUNNING agents:
`V24` dropped the one-RUNNING-per-workspace index (qits-1212). A slot is a
`workspace` row with **no branch**; the old work columns on `workspace` stay, unread by slots (D20).

**The doors write rows; one scheduler talks to the daemons.** Dispatch, delivery, entity, a person's
create and a release's resolution write the agent row and answer at once. `AgentService.reconcile`
makes every daemon call, one pass at a time, kicked after each door and every
`qits.workspace.agent.sweep-interval` (`wiring/AgentSweep`):

1. **Resume** — every YIELDED agent with something to hear is RUNNING again at once, even beside
   another RUNNING agent. A resume goes before a new agent (D17).
2. **Place** — a QUEUED agent goes to an empty workspace of its wrapper and posture, else to a new
   one while no TARGET workspace of this service waits QUEUED for a slot (an admin agent always gets
   a new DIRECT admin workspace, D10), else to a workspace whose agents have all yielded, else it
   waits. Never to a workspace where another live agent (ACTIVE or OBSOLETE) of the repository holds
   its wrapper branch: git checks a branch out in one worktree at a time (qits-1217).
3. **Yield** — a RUNNING agent waiting for input longer than `qits.workspace.agent.yield-after` (30 s)
   yields when another agent wants its slot (D14, D16). A yield frees the slot and nothing else: no
   daemon call, the harness keeps running, and its terminal, turns and entity updates keep working
   (qits-1212). The write is the yield: a candidate that is no longer RUNNING when its row is read
   again yields nothing, so nothing is logged and nobody is placed on its account. A yielded agent
   that works again (`awaitingInput: false`, say a person typed in its terminal) is RUNNING again.
   The wait is counted from the later of `waiting_since` and `last_activity_at` (qits-1511), so a
   turn just typed — which leaves `waiting_since` until its BUSY frame — is not yielded from under
   it; an agent on a declared wait yields at once only while no frame or turn came since it stopped.
4. **Nudge** (qits-1460, `nudgeIdle`) — nothing else wakes a dispatched agent that ends its turn
   without declaring a wait or a block: it used to sit idle, its item showing "waiting for you",
   until a person noticed (qits-1149, qits-1163 and qits-1383 sat ~8 h on 2026-10-19). An agent is
   nudged when it is ACTIVE, RUNNING or YIELDED with no start refused, on a work item that is not
   VERIFIED, DONE or DROPPED and not between phases — REFINED, READY_FOR_DEV or IMPLEMENTED
   (`BETWEEN_PHASES`, case-insensitive): there the phase is finished and the next turn is the
   platform's own dispatch (a person approves REFINED→READY_FOR_DEV, the platform starts implement
   and verify), so "carry on with the phase" would push it into work nobody started — with no pending text, no OPEN wait and no explicit block
   (`entity_blocked` with `block_source` `EXPLICIT` or `BOTH`, or no source at all; the derived
   `AGENT_WAITING` block alone is the stall itself and does not exempt it) — **except a block the
   agent set itself** (qits-1511): `EXPLICIT` or `BOTH`, with `block_author` equal to its own
   `commissioned_token_subject` — `BOTH` is the normal state of exactly the agent this nudge is for,
   since the derived `AGENT_WAITING` block joins an idle self-blocked agent's explicit one within
   qits-projects' debounce. That agent is nudged like an unblocked one, same clock, limit and
   spacing, with a text of its own ("You blocked <workId> yourself and have no open wait. If what you
   wait on is a release request, a CI run or a work item, declare it with `qits wait …` …; if only a
   person can move it, leave the block …") and `reason` `SELF_BLOCKED`; a block with no source, or
   one set by anybody else, still exempts it — and has been quiet for
   `qits.workspace.agent.nudge-after` (20 min; zero or negative switches it off) counted from the
   later of `waiting_since` and `last_activity_at`. `waiting_since` is the clock because hooks fire
   only at turn boundaries — a 40-minute build sends no frame, so `last_activity_at` alone would
   nudge busy agents — and qits-workspace-daemon's background-work grace is what keeps it honest
   for a Stop that still lists a subagent, a monitor or a session cron. The nudge is the wake path:
   re-checked on the locked row, written to `pending_text` ("You ended your turn with no open wait
   and no block on <workId>. Carry on…; or … `qits wait …` …; or … `work_block` …"), `nudged_at`
   and `nudge_count` stamped (`V28`), `AgentNudged` announced, the pass kicked; a YIELDED agent is
   resumed for it. **At most `qits.workspace.agent.nudge-limit` (3) nudges in a row, each waiting
   twice as long as the one before** (qits-1511): the k-th needs `nudge-after × 2^(k-1)` of quiet on
   the same clock — 20, 40 and 80 minutes by default. Every frame, the nudge's own delivery
   included, moves `last_activity_at`, so the guard is the counter, not activity since the nudge. `nudge_count` goes back to 0 where a turn that is not a nudge is
   written — the dispatch instruction, a delivery (`appendTurn`), a wait's wake — and when a wait is
   declared, never in `drive`. An agent that answers its nudge with nothing stays idle for a person.
   Every agent has a work item (D24), so a person-created agent is nudged like a dispatched one; a
   turn a person types in its terminal does not reset the count. The domain suite pins `nudge-after`
   at a year, because its fixtures backdate `waiting_since`, and drives `nudgeIdle(after, limit)`.

   **Stale idle** (qits-1511, `repairStaleIdle`, run just before the yield): the yield and the nudge
   both need `waiting_since`, and a qits-workspace-daemon from before qits-1511 could swallow a
   turn's Stop — a running workspace keeps the daemon it booted with — leaving an agent idle with no
   `waiting_since` for good (qits-950's agent sat 11 h so on 2026-10-22). So a placed ACTIVE agent
   (RUNNING or YIELDED, no start refused) with no `waiting_since` and no pending text, which its
   daemon reads IDLE or WAITING (never BUSY; no reading or ENDED is left alone) and which sent no
   frame for `qits.workspace.agent.stale-idle-after` (65 min, just past the daemon's 60 min
   background-work grace; zero or negative switches it off), gets `waiting_since = last_activity_at`
   on the locked row, relayed to qits-projects as a waiting flip with cause `StaleIdle` at that
   time. Its quiet clock is then already past `nudge-after`, so it is nudged in the same pass. The
   domain suite pins the key at a year and drives `repairStaleIdle(after)`.
5. **Drive** — a RUNNING agent whose daemon answers is started with `POST agent-worktrees` (its
   instruction, session, entity facts and credential; `mode: INTERACTIVE`, the harness's TUI in a
   terminal), and its pending turn is said at a turn boundary (`/turn`; BUSY waits up to the launch
   window). The daemon holds a turn until the harness has started and types it on one line. The daemon keeps the agent credential in
   memory only, so every placed agent is started again after its daemon connects
   (`onDaemonConnection`) or this service restarts — a YIELDED one included, since it keeps its
   harness (qits-1212). A YIELDED agent is only started: no instruction, its pending turn left on
   the row and its run state YIELDED, for the resume to make it RUNNING and the drive to say the
   turn. The daemon's start is idempotent — a harness that still runs is answered as started and
   left alone — so a start after a lost `started` map never gives an agent a second harness.
   A refused start (409) is stored on the row once per distinct detail (`start_refusal`,
   `start_refused_at`, `AgentDto.startRefusal`/`startRefusedAt`) and tried again every pass; the
   next accepted start clears it (qits-1217). A RUNNING agent whose workspace is FAILED with no
   daemon (it never came up: `TargetLeaseStates.awaitDaemon`, or a FAILED lease) is queued for another
   workspace, the reason stored as its start refusal; `WorkspaceUnqueued` kicks the pass that does
   it, and a FAILED workspace is never offered as a slot. Only an agent that never started there:
   `started_workspace_id` (`V26`) records where a start was last accepted, which is where the
   worktree lives, and an agent whose worktree is in the FAILED volume stays placed and waits for
   that workspace, the reason stored once. A row from before `V26` (null) with a command id counts
   as started where it is placed. A second failure in a row holds the agent
   back from placement 1 min, doubling to 30 min, until an accepted start (qits-1233).
6. **Clean up** (D4, D5, D7) — an OBSOLETE agent whose item is VERIFIED (the status the entity door
   stores) and that has been idle for `qits.workspace.agent.cleanup-idle` (30 s) is checked
   (`cleanup-check`): clean → `DELETE agent-worktrees/{id}`, token deleted, REMOVED; leftover work →
   the D7 prompt is its next turn, never a deletion. It is prompted again only after it has worked.
   A check that says `busy` (background work in the harness after its main turn ended) is neither
   removed nor prompted, nor is a removal the daemon refuses as busy; a check answered 404 is an
   agent the daemon no longer knows, and the row is REMOVED. Every pass that leaves an agent alone
   logs why, once per agent and reason (qits-1233).
7. **Remove empty workspaces** (D2) — a slot whose agents are all REMOVED is discarded: container,
   volume and daemon credential.

Every pending thing is a row, so a restart loses nothing. What is in memory — which agents this
process started on the current daemon connection, the per-agent activity — is rebuilt by the next
pass and the next frames: every placed agent, RUNNING or YIELDED, is started again (qits-1212).

**The doors** (machine doors `{qits:admin, qits:admin-agent, qits:system}`, reads add `qits:agent`):

- `POST /workspaces/api/agent-dispatches` `{repositoryId, wrapperBranch, workId, entityId,
  entityTitle, entityStatus, entityBlocked, ticketId?, epicId?, harness?, instruction}` →
  `{agent, fresh, agentLaunch: SCHEDULED|SKIPPED_RUNNING|QUEUED, agentIdentity}`. Idempotent per
  `workId`: the ACTIVE agent is the answer (`fresh:false`). With none, the item's newest OBSOLETE
  agent on the same wrapper branch is made ACTIVE again (its obsolete and cleanup marks cleared) and
  is the answer, told the instruction whatever its run state (qits-1217): a release of its wrapper
  branch does not end the item, and a second agent on that branch would collide with the
  first's worktree. An OBSOLETE agent on another wrapper branch gets a new agent beside it. `admin: true` is refused (400): an admin
  agent holds the host's docker socket, so only the person door below makes one, never a door
  `qits:system` may press.
- `GET …/agent-dispatches/references?workId=…` (repeating; `ticketId`/`epicId` read as work ids) →
  `entries[].agent`.
- `POST …/delivery` `{workId, text, compactFirst}` → `{agentId, delivered, launched, resumed,
  detail}`; `agentId: null` is "nobody works on it", never a 404, and nothing is created.
- `POST …/entity` `{workId, title, status, blocked, blockSource?, blockedBy?}` and `…/blocked`
  `{workId, blocked, blockSource?, blockedBy?}` → `{agentId, applied}`. `blockedBy` (qits-1511) is
  the subject that set an `EXPLICIT` or `BOTH` block, as qits-projects names it; stored as
  `block_author`, rewritten on every mark.
- `POST /workspaces/api/branches/resolution?repositoryId=` → `{resolved, agentIds}`: every ACTIVE
  agent whose wrapper branch it is becomes OBSOLETE; a branch an agent merely pushed marks nobody.
  An old-model workspace on the branch resolves as before.
- `GET /workspaces/api/agents?workId=&workspaceId=&state=`, `GET /workspaces/api/agents/{id}`;
  person doors `{qits:admin, qits:admin-agent}`: `POST /workspaces/api/agents` (an agent for an
  existing work item, D24; `wrapperBranch` defaults to `ticket|epic/<entityId>`) and `POST
  /workspaces/api/agents/{id}/discard` (`409 AGENT_BUSY` for a still-working harness, or
  `AGENT_HAS_LEFTOVER_WORK`, unless `force`; qits-1238).
- The prompt draft and its images are the agent's: `/workspaces/api/agents/{id}/prompt-draft` and
  `/prompt-attachments` (`V22` re-keyed the tables; composition state was dropped, not migrated).
- `/work/workspaces` and `/work/{ref}/workspaces` keep their shape (the landing app's pact): one entry
  per placed agent, its slot's row id and label, the wrapper branch as `branch`, ACTIVE/INTEGRATED
  (obsolete)/ABANDONED; old-model rows follow.

**The agent credential** (D3, D18): a token of the workspace kind (`workspace-admin` for an admin
agent), context id `agent-<id>`, scoped to the project, with Git refs `[refs/heads/<wrapperBranch>,
refs/heads/<wrapperBranch>-*]` (a trailing `*` is a prefix glob, `GitRefs`). Its claims state
`agent` = the agent id beside `project` (qits-1213); no other workspace credential states it. Minted only where there is
an edge plane, stored on the row, handed to the daemon as the harness `env` (`QITS_TOKEN`,
`QITS_TOKEN_SUBJECT`) and nowhere else, deleted when the agent is REMOVED. `CommissionReconciler`
counts it claimed while the agent is not REMOVED. The slot keeps its own token for the daemon, with
`gitRefs []`: every push is an agent's.

**What the daemon reports** (protocol capability 10): `AgentActivity.agentId` — the activity time,
session id and waiting flip are stored on the agent, and a flip is relayed to qits-projects
(`AgentWaitingReporter`, per agent now); `AgentBranchPushed {agentId, repository name, branch, sha}` —
the name is mapped to a repository id through the agent's project and upserted into `agent_branch`.

**Declared waits** (qits-1153, W1–W8; `AgentWaits`, `AgentWaitController`, `bus/AgentWaitListener`).
An agent says which event it waits on (`agent_wait`: label, qits-eventstream `EventSelection` JSON,
event name, optional expiry) and gets a turn when it happens:

- **Matching.** `AgentWaitListener` is this service's one bus listener: durable, consumer id
  `qits-workspaces.agent-waits` (never change it), signatures `*`. `selects` looks the event name up
  in an in-memory set of the OPEN waits' names (read from the database on first use, refreshed on
  every create, close and scheduler pass). `onFrame` evaluates the OPEN waits of that name with
  `EventSelectionEvaluator`; a match is closed MATCHED and its wake written to the agent's
  `pending_text` in one transaction on this service's datasource, before the claim commits. A throw
  leaves the event owed; a repeat finds the wait closed. With the bus dark (`%dev`, `%test`), tests
  call the listener directly.
- **The wake** is the qits-1152 turn path: a running agent hears it once it is not BUSY, or after
  the launch window regardless; a yielded one resumes for it (resume goes first, D17). The platform
  writes every sentence: label, event name, `occurredAt`, event id, and a `qits events query` line
  that reads the whole event.
  **The payload is never inlined**: it carries text other people wrote, and a turn reads as
  instructions. Only the values at the selection's own paths are shown (JSON-quoted, at most 80
  characters and 3 per path) inside a block marked as event-log data, not instructions.
- **The sweep** (first step of every scheduler pass): an OPEN wait past `expires_at` is TIMED_OUT and
  wakes its agent; an OPEN wait of an agent that is no longer ACTIVE is CANCELLED, with no wake.
- **Waiting on** (W5). Declaring a wait does not yield the agent. While it has an OPEN wait, the
  waiting relay says `cause: "Wait"` and `waitingOn: <labels>` (`AgentWaitingReporter`), and an agent
  waiting for input on a wait gives up its slot at once when another agent wants it (no
  `yield-after`); its harness keeps running.
  A match or timeout relays `waiting: false` (`WaitMatched`, `WaitTimedOut`).
  **Every relay names the OPEN waits, busy or idle** (qits-1460): `waitingOn` is the labels of the
  waits still open whatever `waiting` says — after a match or timeout, the waits that stayed open,
  read after the close committed — so an item whose agent is working still shows what it waits on.
  A declare and a cancel always relay, with `waiting` = whether the agent waits for input and cause
  `Wait` while a label remains, else `WaitCancelled`; a wait is normally declared mid-turn, which
  used to relay nothing at all. A busy relay keeps its own cause (`AgentDispatched`,
  the hook); qits-projects stores the label regardless and the cause only while
  waiting. The one relay that names no label is a removal's (`AgentRemoved`): its OPEN waits are
  cancelled by the next sweep, which relays nothing, so the label would outlive the agent.
  `AgentDto.waitingOn` shows the labels,
  `AgentDto.waitingOnSince` when the oldest of them was declared (qits-1229; not `waitingSince`,
  the hook's clock). A wait opening or closing fires `AGENTS` on the agent's workspace channel after
  it commits; an agent with no workspace fires nothing.
- **Limits.** 8 OPEN waits per agent, timeout at most 30 days, label at most 200 characters (default:
  the selection in one line). The native image registers `EventSelection`, its `Condition` and `Op`
  on `AgentWaitController`.

**The lifecycle events** (qits-1236; the records in `workspaces-events/`, the port
`control/LifecycleAnnouncer`, the publisher `bus/EventstreamLifecycleAnnouncer`). What the scheduler
does to an agent and its workspace is announced on the bus, one event per fact, so an agent or a
person can wait on it — `qits wait --event AgentRemoved --when 'agentId=<id>'` selects on the
payload's top-level fields:

| Event | Fields (besides `eventId`/`occurredAt`, which ride the envelope) | Reason words |
|---|---|---|
| `AgentPlaced` | `agentId`, `workId`, `workspaceId`, `repositoryId` | |
| `AgentStarted` | the four, `commandId`, `launched`, `resumed` (the daemon's answer) | |
| `AgentStartRefused` | the four, `detail` (the daemon's refusal, or the never-came-up sentence) | |
| `AgentRequeued` | the four (`workspaceId` is the one it left), `reason`, `detail` | `WORKSPACE_FAILED`, `WORKSPACE_GONE` |
| `AgentYielded` | the four | |
| `AgentNudged` | the four, `nudgeCount` (this nudge included), `reason` | `IDLE`, `SELF_BLOCKED` |
| `AgentResumed` | the four, `reason` | `TURN_PENDING`, `WORKS_AGAIN` |
| `AgentObsolete` | the four, `branch`, `version`, `commitSha` (the release's refs; both absent for a dropped item) — one per agent | |
| `AgentCleanupDeferred` | the four, `reason`, `detail` | `ITEM_NOT_CLOSED`, `RECENTLY_ACTIVE`, `TURN_PENDING`, `HARNESS_BUSY`, `DAEMON_NOT_CONNECTED`, `NO_DAEMON_CLIENT`, `CHECK_UNANSWERED`, `BUSY_IN_BACKGROUND`, `REMOVAL_UNANSWERED`, `LEFTOVER_WORK_PROMPTED` |
| `AgentRemoved` | the four, `reason`, `harnessStopped`, `detail` | `OBSOLETE_NEVER_PLACED`, `OBSOLETE_WORKSPACE_GONE`, `UNKNOWN_TO_DAEMON`, `RELEASED_IDLE_CLEAN`, `DROPPED_IDLE_CLEAN`, `DISCARDED` |
| `AgentHarnessExited` | the four, `commandId`, `status`, `exitCode`, `stoppedBy` | `stoppedBy`: `DAEMON_REMOVE`, `DAEMON_YIELD`, `KILLED`, `SELF` |
| `WorkspaceStarted` | `workspaceId`, `repositoryId` | |
| `WorkspaceFailed` | `workspaceId`, `repositoryId`, `runtimeError` | |
| `WorkspaceRemoved` | `workspaceId`, `repositoryId`, `reason` | `NO_AGENT_LEFT` |

The rules they keep:

- **Ids are strings, flat and top-level.** `workspaceId` is the workspace ROW id (the number in
  `/workspaces/daemon/<id>`), not the `ws-…` name; `workId` is the agent row's `work_id`. An absent
  value (a never-placed agent's workspace) is an absent key.
- **No text a person or an agent wrote.** No instruction, pending turn, prompt draft, work item title
  or a release's `result`; a `detail` is a sentence this service wrote, or a daemon's refusal.
- **Published after the write committed**, from the site that wrote it: the
  `QuarkusTransaction.requiringNew()` block has returned (or, for `WorkspaceRemoved`, the
  `@Transactional` `discardWorkspace`). Where the write happens inside a lambda (`resumeYielded`,
  `onActivity`, `resolveReleasedBranch`, `markRemoved`) the event is built there and announced after.
- **Once per transition.** `AgentRemoved` only from the call that flipped the row;
  `WorkspaceStarted`/`WorkspaceFailed` only when the runtime status changed (a RUNNING row found
  running again by an inventory, or re-stamped by the DIRECT ladder's already-running short-circuit,
  says nothing); `AgentStartRefused` under the `lastRefusal` throttle; `AgentCleanupDeferred` under
  the `lastSkip` throttle, which is keyed on the reason WORD, so a detail that moves under one reason
  (the workspace's runtime status, the item's status) is neither a new log line nor a new event.
- **`harnessStopped` is true exactly when `AgentDaemon.remove` answered OK** for that removal.
- **`AgentHarnessExited` is wired from the daemon's `AgentHarnessExit` frame** (capability 11):
  `WorkspaceDaemonRegistry.onMessage` dispatches it to `AgentService.onHarnessExited` on
  `sinkDispatch`, never the socket thread, skipping a null `agentId` (the sign-in terminal, which no
  agent of this service owns). An older daemon simply never sends the frame, so the event never
  fires for it — nothing here falls back to inferring an exit any other way.
- **Off the caller's thread, in order.** `EventstreamLifecycleAnnouncer` reads the causation scope on
  the caller's thread and publishes on one thread of its own (qits-ci's `RunnerLifecycleAnnouncer`),
  with a request context activated for the outbox's fallback. `bus/EventWireReflection` registers
  every record for the native image; `EventWireReflectionTest` guards the list.
- **The tests** assert at the port: `RecordingLifecycleAnnouncer` (domain tests) records, and
  `LifecycleEventsTest` and `WorkspaceTargetPlacementTest` drive the transitions;
  `workspaces-events/…/LifecycleEventsContractTest` pins every wire name and payload field.

**What a slot would lose is asked per agent** (qits-1263, `AgentWorktreeGuard`). The daemon sends
no `GitStatus` frame since protocol 10, so there is no slot-wide clean/dirty any more:
`WorkspaceDto.clean` is always null, and `WorkspaceGitStatus` is gone. Every verb that destroys a
container — recreate (both placements), abandon, integrate's source check, branch cleanup, a
release's resolution (which only logs) — asks the cleanup check of each live (ACTIVE or OBSOLETE)
agent on the slot, the check an agent's discard asks. A slot with no live agent passes whatever its
daemon's state; an agent the daemon does not know (404) holds nothing. Leftover work, a busy harness
and a live agent whose daemon cannot be asked refuse with a 400 naming the agent, `code`
`AGENT_HAS_LEFTOVER_WORK`, `AGENT_BUSY` or `AGENT_DAEMON_UNREACHABLE` — 400 and not discard's 409,
because recreate has always refused with 400 and qits-landing-app's pact pins it. Abandon,
integrate and cleanup ask only when a DIRECT container exists, as before; recreate always asks.

**Capture files a ticket and stops** (D25): `POST /workspaces/api/capture` asks qits-projects `POST
/projects/api/work` for an IMPROVEMENT ticket in the captured repository's project (`WorkFiler`,
`wiring/HttpWorkFiler`) and answers `{work: {id, qualifiedId}}`. No workspace, branch or agent.

**A start may carry its own `qits-agent-config` checkout** (qits-1382):
`qits.workspace.agent-config.repository-id` and `qits.workspace.agent-config.repository-path`
(`<projectId>/<repoName>`), read in `AgentService` beside its other config. Blanking either
switches the feature off — `AgentDaemon.Start.agentConfig()` is null, exactly as it is where
neither is set. With both set, the daemon checks the agent's own branch of that repository out
under `/workspace/agent-config/<agentId>/tree` and launches Claude with it in place. The branch the
daemon pushes back is named for the agent's wrapper branch, and `releaseAgentBranches` is what
releases it, at VERIFIED, behind manual-review approval — the same gate every other agent branch
gets.

**Known limits.** The person door derives the wrapper branch from what it is told; it does
not read the work item. `MountedClaudeTranscripts` reads a slot's sessions per agent directory, keyed
on the wrapper's name.

## The credential a workspace container holds

A workspace container gets a **credential of its own** — commissioned at provision, handed back at
teardown. On a deployment with an edge plane that is a workspace token (`QITS_TOKEN`), for TARGET
rows always and for the DIRECT admin and editor rows since qits-1084; on a DIRECT row with no edge
plane it is an **idp client pair**, injected as `QITS_COMMISSIONED_CLIENT_ID`/`_SECRET`. Most of what
follows was written about the pair and holds for the token by the same reasoning; the two token
paragraphs below say where they differ. README has the operator's half; here is what the code
decides and why.

**The lifetime is the CONTAINER's, and that is the one thing to keep straight.** Not the row's:
`deleteContainer` leaves an ACTIVE workspace with no credential, and the next ensure commissions a
fresh one. Not a token's either — the pair is what lives long and tokens are re-minted underneath it,
which is what makes "no TTL, no refresh" a property rather than an omission.

**The pair lives on the workspace row, and the container factory looks it up.** It is not handed to
`forWorkspace` as an argument, and the reason is `ContainerRuntime.start`: the orchestrator has no
start verb, so a stopped container is started by presenting its spec **again**, under
`Recreate.ifChanged`. A spec whose environment differs from the running container's is a spec change,
and the orchestrator **replaces** the container — writable layer and all. A credential that arrived
as a parameter on the provision path and was absent on the start path would therefore destroy a
container on every resume. The row makes the spec reproducible at every ensure, which is also why the
**secret** is a column and not something re-fetched: qits-idp hands it out once.
`WorkspaceContainersTest` asserts the two specs differ, which is the same fact from the other side.

**The commission says which project, and that is the whole of per-context scoping on this side.**
`commissionFor` resolves `repoId` to its project through `RepositoryLookup` and passes it to
`CredentialCommissioner.commission(rowId, projectId)`, where it becomes `claims.project` on the
commission request. The row id and the project are deliberately different arguments carrying
different facts: the row id is the `contextId` a reconcile compares against live workspaces, and the
project is the scope a resource service judges the credential on. **An unresolved project is null and
means unscoped**, matching `WorkspaceContainerFactory`'s standing reading that a project the registry
cannot name costs a label and never a workspace — the wider credential is a worse answer than no
workspace only if you have not had a registry blink during a launch. It is never sent as `"*"`;
qits-idp refuses a commission that tries to widen itself, and asking would be asking for the thing
this change exists to stop granting.

**Four seams, and the reason there is no `WorkspaceResolved` observer.** Commissioning is in
`provisionContainer` alone — the fresh arm of `ensureContainer` and, through it, recreate, so nothing
else has to remember. Decommissioning is at three: `doDiscard` (every resolution verb), the
branch-gone abandon in `ensureContainer`, and `deleteContainer`. An observer on `WorkspaceResolved`
would cover the first two and **not** the third, which fires no event at all — so one mechanism would
still need a second call site, and two mechanisms for one rule drift apart. It would also put an HTTP
call inside the resolving transaction, which is fired synchronously so observers can join it. Each
call therefore sits beside `containers.rm`, itself an HTTP call and best-effort for the same reason.

**Commissioning fails a launch; decommissioning never fails a teardown.** The first is patient
(`qits.workspace.commission.patience`, and 401/403 are held through for the idp-cutover reason
`WorkspaceContainers.holdThrough` records), then throws — a workspace with no identity would pull as
nobody and only discover it much later. The second logs and moves on, because everything after an
accepted removal must not pretend it did not happen. **`CommissionReconciler` is what makes that
safe**: hourly and at boot it asks qits-idp what it holds for this service and gives back every
`workspace`-kind row no ACTIVE workspace claims **by client id** — so a recreate's replaced pair and a
crashed teardown's leftover are both orphans the moment they stop being claimed. It only ever deletes
what that listing just returned, and an unreadable listing comes back empty, so a blip reaps nothing
rather than everything.

**An ADMIN row commissions kind `workspace-admin`, every other row `workspace`** (qits-628
follow-up). `commissionFor` reads `wt.admin` beside `wt.editor` and passes it to
`CredentialCommissioner.commission(rowId, projectId, gitRefs, admin)`; `contextKindFor(admin)` picks
the kind, which qits-idp issues `qits:agent` + `qits:admin-agent`. The flag changes nothing else in
the request and never reaches the spec. The reconcile matches both kinds through
`CredentialCommissioner.isWorkspaceKind`. A live admin container keeps its old `workspace` pair until
it is recreated or deleted. The token takes the same flag (`commissionToken(rowId, projectId,
gitRefs, admin)`, qits-1084): a TARGET row is never admin and passes false, a DIRECT admin row
true.

**A TARGET row holds a workspace token per lease** (qits-625, qits-802, epic qits-1481): one opaque
`qits_tok_` of the `workspace` kind (`CredentialCommissioner.commissionToken`, `POST
/idp/api/tokens`), minted by the TARGET start immediately before `createLease` and outside every
transaction, stored on the row (`V13`) and composed into the spec as `QITS_TOKEN`/`QITS_TOKEN_SUBJECT`.
Its lifetime is the lease's: deleted when the lease reports ENDED, EXITED or FAILED, and a token a
row still holds at its next start is given back before a fresh one is minted. The reconcile reaps
every `workspace` token no ACTIVE row holds for a container — a DIRECT row, a TARGET row with a
lease, or one a retired runner still runs (sparing one younger than `TOKEN_GRACE`). **Absent is not
supported here**: a TARGET container has no other credential, so a start that cannot mint fails the
row with `WORKSPACE_TOKEN_UNAVAILABLE` and asks for nothing. The test double mints tokens by default for
that reason (`unwireTokens()` is the no-issuer case); its pair half still starts unwired.

**A DIRECT admin or editor row holds a workspace token too, whenever there is an edge** (qits-1084).
Both stay DIRECT for good, but the hourly-minting pair is no longer their credential on a deployment
with a public domain. `commissionFor` asks `WorkspaceAddressPlanes.plane()` first: when it resolves,
the container is minted the TARGET row's `qits_tok_` instead — `workspace-admin` for an admin row,
`workspace` for the editor, unscoped because the editor names no repository, stating the same Git
refs the pair would have — through the same `mintToken`/`storeToken` helpers `mintLeaseToken`
uses, onto the same three `V13` columns. When it does not (no public `QITS_DOMAIN`), the pair is
commissioned exactly as before and a WARN says why; the provision is never failed over a missing
plane. A failed mint fails the provision, as a failed commission does. `decommissionFor` runs first
either way and clears both pair and token, so a row holds one or the other and never both — and a
row switches arm at its next container, never under a running one.

- **The spec follows what the row holds.** `WorkspaceContainerFactory` asks
  `WorkspaceCredentials.tokenFor` first; a token row gets `QITS_TOKEN`, `QITS_TOKEN_SUBJECT`,
  `GIT_CONFIG_GLOBAL` and `QITS_GIT_AUTH_HOST` on the plane's githost (`tokenEnv`), and its daemon
  URL, MCP URL and git base from the plane (`addressEnv`) — both shared with
  `RunnerWorkspaceSpecs` — and none of the pair block: no `QITS_COMMISSIONED_CLIENT_*`, no token url,
  no audience. A token only works through the edge (which exchanges it for a JWT), which is why the
  addresses move with it. Everything else DIRECT stays: qits-net, the extra host, the docker socket,
  the editor's port and projects, the host-to-daemon tunnel. A pair row's spec is byte for byte what
  it was; `WorkspaceContainerFactoryGoldenSpecTest` pins both. A token row whose plane has since gone
  fails its start with `EDGE_PLANE_UNCONFIGURED` rather than launch on addresses it cannot spend.
- **Every check reads what the row holds, not its placement**: the socket binding, the tunnel's
  dial-back and a dispatch's `agentIdentity` all go through `Workspace.boundSubject`.
- **Teardown** is the TARGET row's: `deleteDirectContainer` and the DIRECT arm of `doDiscard`
  delete the token beside the pair (`deleteToken(clearToken(…))`); recreate, abandon and a failed
  provision go through `decommissionFor`, which always did both. The reconcile already reaps both
  token kinds by `isWorkspaceKind`.
- **A live container keeps what it was launched with** until it is recreated or deleted — a stop and
  start re-presents the same spec — so an admin or editor container commissioned before this change
  keeps its pair until then.

**Absent is a supported configuration in two spellings and they behave identically**: no
implementation of `CredentialCommissioner`, or one wired against no issuer. The switch is
`quarkus.oidc-client.qits.client-enabled` — the extension's own, read a third time here for the
reason `ContainersClientProducer` reads it a second time (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4).
There is no key of ours, and there must not be.

## The Git refs a workspace may push

None. Contracts C4 and C5 of the superproject's `principal-bound-git-refs-plan.md` gave each
workspace a list of its own, stated in its commission and narrowed when another workspace took a
branch. Since qits-1152 the push scope is the **agent's**: `AgentService.gitRefsFor` works it out,
`agent_worktree.git_refs` stores it, and the agent's token carries it (see "Agents and the
workspaces they run in"). A workspace is a slot with no branch, and its credential — pair or token —
states `gitRefs: []`: it may push nothing, and the daemon pushes with each agent's token.

- **The list is stated, never omitted.** An absent `gitRefs` would let the credential push every
  ref, so `commissionFor` and the TARGET mint always pass `List.of()`.
- **The narrowing is gone** (qits-1385): the narrowing bean, the `PUT …/git-refs` updates on both
  doors and the reconcile's resend were removed with nothing to narrow. `V27` drops
  `workspace.git_refs` and `git_refs_pending`; `agent_worktree.git_refs` (`V21`) stays.
- **The 400 fallback is in `IdpCredentialCommissioner`, and it fails closed.** A 400 to a commission
  that states a non-empty list means qits-idp refused that list; the commission is asked again with
  `gitRefs: []` and an ERROR names the row and the idp's reason. It is never asked again without
  `gitRefs`. A 400 to `[]` is a failed launch.

## Admin workspaces: the one privilege a workspace can be granted

An **admin workspace** is an ordinary workspace whose container holds the **host's docker socket**,
so that platform administration can be done from inside a workspace. Since qits-1152 it is asked for
per agent — `admin: true` on `POST /workspaces/api/agents` or the dispatch door — and an admin agent
runs only in an admin workspace (D10), which the scheduler creates DIRECT for it. It is the only thing
about a workspace container that is not the same for every workspace.

**A container holding that socket is root-equivalent on the host.** Everything below follows from
that one sentence, and each piece is a place the property could be lost:

- **The posture is a column on the row** (`Workspace.admin`, `V4`), not a flag on a launch. The
  orchestrator has no start verb — a stopped container is started by presenting its spec *again*
  under `Recreate.ifChanged` — so a posture that arrived as an argument on the provision path and
  was missing on the start path would make every resume a spec change, and a spec change **replaces
  the container**. That is the same reasoning the commissioned credential's columns carry, and it is
  why `WorkspacePostures` is a lookup by row id rather than a parameter threaded through
  `ContainerRuntime`.
- **It is decided once, in the request that created the workspace.** There is no promote verb and
  there must not be one: the point of the posture is that the socket belongs to the few workspaces
  somebody deliberately created for it, and a workspace that has been running for a week cannot
  acquire it. `recordWorkspace` is the only writer, private, with both its callers in
  `WorkspaceService`.
- **Every absence falls to false.** No port wired, no row, a read that threw — all of them mean *no
  socket*. That asymmetry with the credential lookup beside it is deliberate and is asserted:
  a credential lookup that stumbles costs a container something it was meant to have, while a
  posture lookup that stumbles must never **give** it something it was not.
- **Nothing else about the container changes.** Same image, same user, same limits, same mounts,
  same environment — `WorkspaceContainersTest` makes that claim as the admin spec with the socket
  taken back out, which fails if any other field moved. A posture that quietly relaxed the sandbox
  as well would be a privilege nobody asked for riding along with the one somebody did.
- **The socket is usable despite the host uid because qits-containers joins the socket's own
  group** beside the bind (`--group-add`, read off the socket by the orchestrator —
  qits-containers-service's README, "The docker socket"). A workspace-side group would be a privilege assembled here rather
  than granted there, and this service does not get to name a group. The client that talks to it is
  the workspace image's business: `components/qits-workspaces/qits-workspace-oci` carries the docker
  CLI, because a socket with nothing to speak to it is a bind and not a capability.
- **The read model says so.** `WorkspaceDto.admin` rides the listing and the create response,
  because a client that cannot see which workspaces are privileged cannot say so, and "which ones
  hold the socket" is the question the whole posture exists to keep answerable.

Who may ask is `WorkspaceController`'s standing `@RolesAllowed("qits:admin")`: creating **any**
workspace already requires the platform admin role, and the socket is granted per workspace rather
than per caller. A second role invented here would be a vocabulary qits-idp does not issue.

## Placement: DIRECT and TARGET (epics qits-624, qits-1481)

A workspace's container runs either **DIRECT** (the platform host, through qits-containers) or
**TARGET** (a WORKSPACE slot of an execution target, under a qits-system lease). `Workspace.placement`
is written once, by `recordWorkspace`, from `WorkspacePlacements.forNewRow(admin)`: **a regular
workspace is always TARGET, and only admin workspaces are DIRECT** (qits-774). The workspace runner
this service registered and spoke to over `/workspaces/runners/socket` — its registry, health gate,
doors (`/workspaces/api/runners/**`), install line, login command, pins, `qits.workspaces.runner.*`
keys, protocol and toolkit dependencies, golden-master frames and pacts — was deleted in qits-1532;
`V30` dropped its table. The refusal codes live in `error/TargetRefusals`.

**No regular row is DIRECT, and three things hold that** (qits-780): `ck_workspace_direct_only_admin`
(an ACTIVE row is TARGET or admin; `V20`, `V30`), the create rule above, and **the router's
refusal** — `WorkspacePlacements.requireDirectAllowed(Workspace)`, called at every DIRECT branch
before `ContainerRuntime` is touched. Tests that prove the DIRECT ladder use **admin** rows. A
discard never deletes a main workspace's branch (qits-780), on both placements.

- **One branch per verb, at its top.** `beginEnsureContainer`, `stopContainer`, `deleteContainer`,
  `beginRecreateContainer` and `doDiscard` each test `placement == TARGET` first and hand the row to
  the "TARGET placement" section of `WorkspaceService`.
  `WorkspaceTargetPlacementTest.aDirectRowNeverReachesThePortAndKeepsItsLadder` is the seam's test.
- **The port is `control/TargetPlacement`**, implemented by `targethost/TargetLeasePlacement` over
  qits-system's doors (`targethost/SystemLeases`: `createLease`, `listLeases`, `getLease`,
  `endLease`, `purgeWorkVolume`, `listTargets`; `qits.system.url`, the `qits` oidc client's bearer,
  `@DefaultBean` so the suites' `FakeTargetPlacement` wins). Absent reads as "no target can be had".
- **Start** (`beginTargetStart`): the edge plane first (`EDGE_PLANE_UNCONFIGURED` fails the row);
  a previous lease the row still names is ended and waited for (`TargetPlacement.ENDING_DEADLINE`,
  else 504 `TARGET_TIMEOUT`), since qits-system holds one live lease per owner ref; then the lease's
  token is minted (`WORKSPACE_TOKEN_UNAVAILABLE` fails the row), the spec composed
  (`RunnerWorkspaceSpecs`: image, env without `HOME`/`CLAUDE_CONFIG_DIR`/`BUILDKIT_HOST`, user
  `1000`, the `qits.workspace.*` limits), and `createLease` asked with `ownerRef <rowId>`, `workKey
  ws-<rowId>`, `priority 0`. A 4xx refusal fails the row (`TARGET_REFUSED: …`); no answer, a 5xx or
  a 409 is 409 `TARGET_UNAVAILABLE` with the row untouched; either way the token is deleted. The
  lease is recorded by a compare-and-swap (`WorkspaceRepository.recordLease`), the row QUEUED with
  the lease's `detail` as its `runtimeError` (the DTO's existing reason field).
- **The lease's state is the row's** (`control/TargetLeaseStates`, the runner's `RunnerClaims`
  successor): `targethost/TargetLeaseEvents` hands it every `TargetLeaseChanged` of owner
  `qits-workspaces` and slot type WORKSPACE (a live `QitsEventListener` on a transcribed record,
  `targethost/TargetLeaseChanged`), and every
  `qits.workspaces.target-leases.reconcile-interval` (PT30S) the listing, where a lease no longer
  listed reads as ENDED and a live lease no row names, older than two minutes, is ended. QUEUED →
  QUEUED (detail shown), GRANTED → PROVISIONING (`target_id`), RUNNING → the daemon's self-clone is
  awaited as rung 3 waits for it, then RUNNING (a RUNNING lease this process never asked for, on a
  PROVISIONING row, is a restart and reads RUNNING at once), EXITED → STOPPED, FAILED → FAILED with
  the detail, ENDED → STOPPED with lease and target cleared. EXITED, FAILED and ENDED delete the
  lease's token; EXITED and FAILED also end the lease, so the next start can ask for another. A row is
  found by its `lease_id`, never by the owner ref.
- **Stop** ends the lease and writes STOPPED; the ENDED that follows clears it. **Delete-container**
  ends it, waits for ENDED, purges `ws-<rowId>`, and clears `lease_id`, `target_id`,
  `adopted_volume` and the row's `legacy_placement` entry. **Recreate** is that, then a start. A
  **resolution** ends the lease after its commit; the resolved row keeps naming it until it is ENDED,
  when its work volume is purged.
- **Legacy adoption.** A row in `legacy_placement` asks its first lease with `adopt{container,
  volume}` = `qits-workspaces-runner-ws-<rowId>-<oldRunnerId8>` and `pinTargetId` the target whose
  `engineId`, else `hostname`, else name matches the runner's (`listTargets`'
  `engineId`/`hostname`; the retired runners never reported either, so for their rows the name is
  the practical match). **No match is a wait, never an unpinned lease** (which would start the
  workspace fresh elsewhere and strand its volume): the row stays QUEUED with no lease and no token,
  `runtimeError` `waiting for target <oldRunnerName> to be installed (it holds this workspace's
  volume)`, and every lease reconcile retries it (`WorkspaceService.startWaitingLegacyRows`); an
  unreadable target listing waits too. Only an entry that names nothing of its runner (no name,
  engine id or host name: the runner row was gone at `V30`) counts its volume as gone, and is asked
  for unpinned, adopting nothing, its entry deleted. A waiting row is not counted by the scheduler's
  capacity rule, which counts only QUEUED rows holding a lease. The
  name is kept on the row (`adopted_volume`) and every later lease carries it — the runner mounts an
  adopted volume only when the lease names it. The entry goes once the lease is RUNNING. A row a
  retired runner still runs (RUNNING with no lease) is left alone until it is stopped or started.
- **`targethost/LegacyRunnerClients`** gives back, at every boot, the idp clients and tokens of
  kinds `workspaces-runner` and `workspaces-runner-registration`; idempotent, a failure is a WARN.
- **The agent scheduler** asks for a new regular workspace only while no TARGET row waits QUEUED
  (`AgentService.targetCapacity`): qits-system decides who has room, this service only keeps from
  piling up waiting leases.
- **No direct path to any daemon** (qits-812, qits-780): a TARGET row is `NOT_CONNECTED` in
  `DaemonProxyTargets` without asking the runtime; its daemon dials home through the edge.

## The contract with qits-projects: golden masters in, a pact out

Epic qits-546. `HttpRepositoryLookup` makes two calls to qits-projects — `getRepository` behind
`find`/`require`, `listProjectRepositories` behind `listByProject` — and both ends of that contract
are now files rather than prose.

**In: the provider's golden masters.** qits-projects records what it answers per provider state and
publishes the tree as `eu.wohlben.qits:qits-projects-golden-masters`, pinned in the root pom as
`qits.projects-golden-masters.version` (test scope, declared version-less in `service`). It moves
like every internal pin — a qits-maintenance bump on that property, gated here — and
`testing/contracts/GoldenMasters` (service test sources) reads `golden-masters/index.json` off the
classpath. `GoldenMasters.body(state, operationId)` is what the fakes serve now:
`HttpRepositoryLookupTest`'s ordinary answers and `stories/support/StoryPeers`' qits-projects routes
(StoryPeers lays each registered row's id/name/projectId/mainBranch over the recorded repository;
everything else — `archetype: SERVICE`, `backupUrl`, `declared`, the listing's `wrapper` shape — is
the recording's). The deliberately malformed answers in `HttpRepositoryLookupTest` (no name, an
unknown field, a 500, not JSON) stay hand-written: they are what the provider does not say.

**Out: the consumer pact, `pacts/qits-workspaces-service_qits-projects-service.json`.** Consumer
`qits-workspaces-service`, provider `qits-projects-service`: repository names, so a frontend and a
backend of one component stay apart. Pact V4. Three test classes in `service/…/wiring/`:

- `ProjectsContract` — the table, one row per **(trigger, call)**. The trigger is the workspaces
  entry point that reaches qits-projects, named by its openapi `operationId` (or the daemon frame), and
  it is what tells apart the rows that send the identical request. Since qits-1152: `dispatchAgent`,
  `createAgent`, `workspace-daemon:agent-branch-pushed` and `captureWork` (on an `@Operation(hidden =
  true)`, so in no document) — the last also files the ticket, `createWork` in state `a project with
  no work`, with this service's own request body. The class javadoc traces each one from the call
  site up; renaming an `operationId` renames a trigger in the pact.
- `ProjectsConsumerPactTest` — a real `HttpRepositoryLookup` with the real generated REST clients
  against a pact-jvm mock server, one mock server per row. **A `@QuarkusTest`, by necessity**: the
  reactive REST client refuses to build outside a running application ("needs to be built within the
  context of a Quarkus application with a valid ArC (CDI) context running"), and a hand-rolled client
  would prove a contract for a client that does not ship. It boots the default profile, so no
  `@TestProfile`. It drives pact-jvm's programmatic `runConsumerTest` rather than
  `PactConsumerTestExt`: the extension serves a whole pact from one mock server, which cannot tell
  five identical `GET /projects/api/repositories/{id}` rows apart, and injects that server as a
  method parameter across Quarkus' classloader.
- `ProjectsPactFileTest` — plain JUnit. pact-jvm's writer serialises the table's pact (raw copy in
  `service/target/pacts/`), the test normalises it (interactions sorted by description then state,
  `metadata.pact-jvm` stripped so a library bump is not a contract change, 2-space indent, trailing
  newline) and **compares** it to the committed file. `-Dgolden.update=true` (or
  `QITS_GOLDEN_UPDATE=true`) rewrites it — the provider's switch, through a copy of its
  `GoldenFiles`. It also asserts the references below on every interaction of the committed file.

**Matchers come from the index's `frozen` lists, never from a value's shape**: `ids` → `uuid`,
`instants` → an ISO-8601 regex, `strings` → type, `listFilteredTo` → `minArrayLike(recorded
length)`, every other leaf → type. Every other non-empty array is `minMaxArrayLike(n, n)` against ONE
template merged from all recorded elements, because qits-projects does not guarantee element order;
a leaf that is null in one element and set in another becomes a V4 `type OR null` combined matcher.

**Every interaction carries `comments.references`**: `qits-call` `{app: qits-projects,
operationId}` and `qits-trigger` `{kind, app: qits-workspaces, operationId | event | schedule}` (or
`{kind: ui, app, interaction}`). **pact-jvm 4.6.21 has no DSL for it** — `comment(text)` and the test
name are all its builder offers — but the V4 model's `comments` map is mutable and its writer emits
every key, so `GoldenMasters.interaction` puts the group there directly; no post-processing of the
written JSON. `ProjectsPactFileTest.everyInteractionCarriesBothReferences` is what notices if a
pact-jvm upgrade stops writing it. `trigger` is required: a null one throws.

**Published only on change.** `release.yml` declares one `contracts.pacts` entry per provider
(`qits-events-service`, `qits-projects-service`, `qits-idp-service`, `qits-containers-service`,
`qits-workspace-daemon`, and since qits-1481 `qits-system-service`), and
qits-ci packages each `pacts/qits-workspaces-service_<provider>.json` as
`eu.wohlben.qits:qits-workspaces-service-pacts-<provider>`, published only when it changed since the
newest published jar — there is no repository-side gate or module anymore; `pacts-jar/` and
`published-tree-changed.sh` are gone. Since qits-1163
`pacts/` also holds `qits-workspaces-service_qits-eventstream-javalib_qits-events-service.json`,
what the eventstream library asks qits-events for this service, written by
`wiring/EventstreamPactTest` from the pinned `qits-eventstream-contracts` kit and declared in
`release.yml` as `qits-events-service`; a library's pact file is three-part, the provider last.

**The other three providers (qits-1149)** are tables of `testing/contracts/ContractRow`s —
`wiring/IdpContract`, `containershost/ContainersContract`, `daemonhost/WorkspaceDaemonContract` —
each bound to that provider's pinned golden masters (`qits.<app>-golden-masters.version` in the root
pom). A table's consumer test runs every live row against a mock server of its own;
`testing/contracts/ConsumerPactFilesTest` compares the committed files, and fails when a pending row
(`.waiting()`, a state the provider has not recorded yet) is recorded after a pin moves — switch it
on then. `wiring/OutboundCallsGuardTest` reads the calls off the code (the REST client interfaces,
`ContainersClient` references in the bytecode, `DaemonAgentClient`'s `AgentDaemon` methods, every
class holding an HTTP client, the oidc config) and fails on one without a row.

**qits-containers, via its client (qits-1163).** `ContainersContract` keeps this service's own rows —
the `workspace` workload, its place and its real launch spec, against qits-containers'
workspace-place recordings — and takes two things from the client's kit,
`eu.wohlben.qits:qits-containers-client-contracts` (test scope, pinned at
`qits.containers-client.version`, the same release as the client): `ContainersClientPact.VIA`, which
stamps `qits-via` (the client's repository, artifact and version) on every interaction through
`ContractTable.via`; and `ClientUsage.scan`, which `OutboundCallsGuardTest` uses to read the
`ContainersClient` calls off `target/classes`. The committed file is compared with the via version
masked, so a client bump alone is no contract change. The file keeps the two-part name, because the
client ships from the provider's own repository. The kit's own fixed rows (the `editor` workload, a
minimal ensure body) are not used: they pact requests this service never sends.

**qits-system (epic qits-1481)** is `targethost/SystemContract`, bound to
`qits.system-golden-masters.version`, its file `pacts/qits-workspaces-service_qits-system-service.json`
(`release.yml`: `qits-system-service`). Every row is live since the golden masters
2026.1023.213531. They are written against the states qits-system records for this tenant (`a
queued workspace lease`, `a running workspace lease`, `an unused workspace work volume`, `a declared
target`). `OutboundCallsGuardTest`'s `PENDING_ROUTES` is the fallback for a row that waits. The runner's
frame golden masters and message pacts (qits-950) went with the runner.

## Tests

- **App-level config lives in `service/src/main/resources/application.properties`, and the tests
  inherit it.** That file is on the test classpath and Quarkus merges it, so
  `service/src/test/resources/application.properties` holds test-only *overrides* (the test port,
  `flyway…clean-at-start`, the on-disk trees under `target/`) and nothing else. Never
  re-declare a shipped setting such as `quarkus.rest.path` there: the copy is free to drift from
  what ships, and then a green suite proves nothing.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml`
  (`./mvnw -pl service test -Dtest=OpenApiSchemaExportTest`). It runs as a `@QuarkusTest` and indexes
  the test classpath, so a `@Path` resource under `src/test` lands in the committed document unless
  it is `@Operation(hidden = true)` — hence the annotation on `IdentityEchoResource`. The raw Vert.x
  routes and the daemon control socket are in no document; they are not JAX-RS.
- **`mvn verify` passing does not mean the app starts.** Augmentation runs per `@QuarkusTest`
  regardless of packaging, and `FakeRepositoryLookup` is on the *test* classpath — so the suite
  cannot see either a missing `quarkus-maven-plugin` execution or a missing production
  `RepositoryLookup`. Both were invisible here until the jar was actually run. `<packaging>quarkus</packaging>`
  closed the first of those; nothing closes the second, and the native build widened the gap —
  see "the two rules" above for what else only the artifact can tell you.
- `NativeImageContractTest` (one per module) holds the native-image invariants a JVM run *can*
  hold: the shipped datasource being the bare `QITS_RESOURCE_DB_*` contract with no `${user.home}`
  anywhere behind it — the two spellings of "config the binary cannot boot on" this repo paid for,
  `AUTO_SERVER` and a home-rooted file path — every nested record of `QitsConfig` and of
  `CaptureResource`'s payloads present in its `@RegisterForReflection(targets)`, and
  `CaptureCorsRoute` reading an application-owned config key. None of them proves a binary works —
  they prevent the silent re-introduction of what booting one already caught.
- **The suites run on a real postgres they spawn themselves.** Zonky resolves the binaries as
  ordinary Maven artifacts and `EmbeddedPg` starts one child process per surefire JVM — never
  Testcontainers, never a dev service, so the clone-alone no-docker rule survives the move off H2.
  Its port is chosen at run time, which is why the url/username/password are not in any properties
  file: `EmbeddedPgConfigSource` supplies them at ordinal 500, registered through
  `META-INF/services`. Both classes are **copied per module** (`domain`'s `…workspaces.testdb`,
  `service`'s `…workspaces.wiring.testdb`), because sharing them would mean a test-jar dependency
  between two modules that deliberately have none — the same reason the `Fake*` doubles are
  duplicated. **Every (module, datasource) pair names a distinct database** so two suites on one host
  cannot mean the same one.
- **The event bus is dark in `%dev` and `%test`, but its datasource is not.**
  `qits.eventstream.enabled=false` stops publishing and sweeping; Quarkus still opens the connection
  and runs Flyway at boot, so `service`'s `EmbeddedPgConfigSource` supplies the `eventstream`
  datasource's triple beside the `workspaces` one. Without it every run would try to open the
  database a deployment injects, and fail on an unresolvable expression. A test that wants the bus
  real turns it back on for itself against a stub, never against a live qits-events.
- `FakeRepositoryLookup` still wins over `wiring/HttpRepositoryLookup` with no change on your part:
  the latter is `@DefaultBean`, which yields to any other bean of the type. **Keep that annotation.**
  Drop it and the two are an ambiguous dependency — the build fails at `ArcProcessor#validate`, not
  at runtime, and it fails for every test at once. If you ever see the "qits.projects.url is unset"
  warning in a test, a fake is missing rather than broken. Note this is about **injection only**:
  the `@DefaultBean` losing the contest keeps its `@Observes StartupEvent`, so the startup check
  still runs (downgraded to a warning outside `LaunchMode.NORMAL`).
- Deleting a bean is not enough on an incremental build. Removing `UnconfiguredRepositoryLookup`
  left its `.class` in `target/`, and the next `-Dnative` run failed with an ambiguous
  `RepositoryLookup` naming a class no longer in `src/`. `mvn clean` — the symptom names a file you
  cannot find, which is the confusing part.
- `Port already bound: 8081` is settled rather than flaky now: `service`'s test properties set
  `quarkus.http.test-port=0`, so the OS picks and RestAssured is told. It was two failures wearing
  one message — `@QuarkusTest` restarts racing each other for the default test port
  (`migration-plan.md` §9 item 14), and 8081 being where the platform's own npm registry answers on
  the machine this repo is most likely built on. Keep the line; it is a test-only value and belongs
  in that file.
- `TestOrigin.create(dataDir)` builds a real bare origin (master + a diverging feature branch) and
  returns a repo id; pair it with `FakeRepositoryLookup.register`. Where those origins go is
  **`qits.test.origins-dir`, a key no deployment has** — the suite needs somewhere to put a fixture
  git host, and nothing in `src/main` reads it. It is deliberately a **different** directory from
  `qits.workspaces.data-dir`: the service's own tree must not be the tree it fetches from, or a
  suite would prove nothing about the separation. The properties files also set
  `qits.workspace.git.mirror-freshness-ms=0`, because a fixture that changes between two assertions
  must never be served from a window.
- `TestOrigin.recordPushOptions` installs a real `pre-receive` hook on a fixture origin that logs
  each ref with the push options it arrived under, and `pushOptionsFor` reads one back. It is the
  only way to see a `--push-option` from outside the pushing process, and it is what makes "a branch
  create is a quiet push" an assertion about the shipped argv rather than about a constant. Install
  it *after* the fixture commits: it records every push from then on, and the reader takes the first
  line for a ref.
- `TestGit.exec` is how a test runs git in one of those fixture directories. It is a thin static
  helper over `gitmirror`'s `GitCli`, and it replaced `GitExecutor` — a production CDI bean whose
  only remaining callers were tests. `GitCli`'s own properties (the per-line tap, the unterminated
  final line, `GIT_TERMINAL_PROMPT=0`) are asserted in `gitmirror`'s `GitCliTest`, offline and with
  no augmentation, rather than through a `@QuarkusTest` on a bean that only delegated.
- `gitmirror/`'s own suite (`RepoMirrorTest`) is the one that proves the substrate: clone, fetch,
  prune, `ls-remote` answering while the mirror is stale, a branch create and delete arriving as
  pushes, the worktree merge/commit/tag/atomic-push sequence, and a leftover worktree being pruned
  rather than inherited. No Quarkus, no database — 14 cases, about a second. It still covers the tag
  and the atomic push, which no flow in this service uses any more: they are git primitives of the
  substrate, not leftovers of the release door.
- The `Fake*` doubles are duplicated between `domain/src/test` and `service/src/test`. That is
  deliberate and matches the monorepo — the two modules do not share a test classpath.
- **`FakeCredentialCommissioner` starts UNWIRED, and must stay that way for everyone else.** A bean
  in `src/test` is a bean for every `@QuarkusTest` in its module, so a double that commissioned by
  default would put credential environment into every container the suite launches and quietly
  change what dozens of unrelated tests are about. `wire()` turns it on, `reset()` turns it off, and
  a test class that wires it resets in `@AfterEach` — a class that forgets leaks its issuer into
  whatever runs next. Its TOKEN half is wired by default instead (a TARGET start cannot ask for a lease
  without one); that changes no DIRECT container because the suites pin `qits.workspace.domain` empty, so an
  admin or editor row only mints a token where a test installs a `WorkspaceAddressPlanes` mock
  (`DirectWorkspaceTokenTest`, `WorkspaceTargetPlacementTest`).
- Integration tests needing real docker, a built `qits/workspace` image and the daemon binary **are**
  in this repo — `Daemon*IT`, tagged `extended`, `DaemonApiGateIT` the largest of them. They
  self-skip (`assumeTrue`) when docker or the image is absent, and `skipITs=true` is the default
  anyway, so a clone-alone `mvn verify` never reaches them. Run them with
  `./mvnw verify -DskipITs=false`. Keep both properties: the default keeps `mvn verify` runnable
  anywhere, and the self-skip keeps a deliberate `-DskipITs=false` from failing on a machine that
  simply has no image.
  - Their image name comes from `-Dqits.workspace.image=`, falling back to
    `localhost:8081/qits/workspace:latest`. The check behind that fallback is `docker image
    inspect`, which **never pulls** — deliberately, and unlike a real launch, where qits-containers
    pulls the pin (see "The workspace image, and who pulls it"). An IT that pulled 3.4 GB to decide
    whether to skip would not be a self-skip.
    Nothing publishes a `latest` tag, so the fallback normally means "skip", and passing a real
    reference is the way to run them:
    `./mvnw verify -DskipITs=false -Dqits.workspace.image=localhost:8081/qits/workspace:<calver>`.
    The fallback is deliberately not a copy of the pin; the release train moves the pin and would
    not move five test literals. It used to be the bare `qits/workspace:latest`, which was a
    hand-built local tag on one machine — the whole disease the pin exists to end, and a spelling
    that must not come back anywhere as a default.

## The story catalogue: the packaged run, and what it documents

`api/TokenValidationBootstrapIT` was the artifact-level integration test the `service` pom's
`native` profile said could not be written yet. It could not, while the deployable refused to start
without a `RepositoryLookup` — a `@QuarkusIntegrationTest` would have failed on the documented
behaviour rather than on a defect. `wiring/HttpRepositoryLookup` replaced that scaffold, so the
address is now something a test profile supplies. It is no longer one IT: it is the first class of a
**story catalogue** under `service/src/test/java/…/workspaces/stories/`, and everything below is
about the whole of it.

**Ten stories, five classes, one launched process.** Every class names
`stories.support.StoryProfile`, because a `@TestProfile` is what failsafe launches a process for and
two profiles would be two qits-workspaces — two boots, two JWKS fetches, two database sets, two
mirror trees, and a diagram whose startup traffic landed in whichever process happened to be
running.

| class | category | what it is about |
| --- | --- | --- |
| `api.TokenValidationBootstrapIT` | authentication | the boot: the JWKS fetch, the commission reconcile, the event-bus listener's first calls, and a bearer cut for another audience |
| `stories.creation.WorkspaceProvisionIT` | workspaces | a workspace provisioned end to end, daemon dial included |
| `stories.operations.OperatorReadsIT` | operations | what a live read costs, and what a stored read does not |
| `stories.refusals.MergeDoorRefusalIT` | refusals | four ways not to get through a door, told at `/branches/merge` |

**Every override the profile sets is a RUNTIME key**, including the two that only look like
environment (`QITS_RESOURCE_DB_*`, `QITS_RESOURCE_EVENTSTREAM_*` — spelled as the deployer spells
them, so the shipped `${…}` expressions stay under test). A packaged process cannot be handed a
build-time key; it would silently take the default, which is this repo's own worst bug class.

**It is opted in by NAME, not by `skipITs`.** The root pom keeps `skipITs=true`, because failsafe
has one run per module and flipping it would turn the five docker-backed `Daemon*IT` back on with
it. Run it — and the composed QA pipeline's verify runs it — as a comma list:

    ./mvnw verify -DskipITs=false \
      -Dit.test=TokenValidationBootstrapIT,WorkspaceProvisionIT,OperatorReadsIT,MergeDoorRefusalIT

Adding a story class means adding it there **and** in `.config/qits/userflow-stories`, which is
what that selection is composed from. `-Dit.test` alone still runs the
surefire suites, which is the honest gate; a run that wants only the stories adds
`-Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false`.

### The far sides, and why there are three of them

Nothing about a packaged run can be faked with a CDI bean: `FakeGitHostAddress`,
`FakeRepositoryLookup` and `FakeContainerRuntime` are on the *test* classpath and the launched jar
has never heard of them. So every peer is a real listener on loopback, and every one of them
**records**, because a story's evidence for what the service did is the far side's own account of
being asked.

- **`stories.support.StoryGitHost` — qits-githost, over real smart HTTP.** The JDK's `HttpServer`
  shelling `git http-backend` over a project root of bare repositories, at the real
  `/git/<projectId>/<repoName>` route. It has to be real: `ConfiguredGitHostAddress` is what a
  packaged process carries, and `RepoMirror.platformArgv` **refuses to run any http(s) git argv**
  without a machine bearer to hang on `-c http.extraHeader`. It also installs the `pre-receive` hook
  that records push options, which is the only way to see a `--push-option` from outside the pushing
  process — and therefore the only way "the trunk push goes quiet exactly when there is somewhere to
  promote to" is an assertion about the argv that ships.
- **`stories.support.StoryPeers` — qits-projects, qits-containers, qits-platform-idp's outbound
  half, and qits-events**, one stub answering as four and told apart by path prefix. Stateless
  except for the container table (a provision asks whether a container is there, puts one, and later
  lists them) and the file-armed `refuse(prefix)` the registry-outage story uses.
- **`MockIdp` — the idp's inbound half**, serving the JWKS the gate validates against. It draws as
  the same node `StoryPeers`' `/idp/*` routes do, because it is the same component.

Both stubs and the git host are started by the **test profile** and read by story methods, which a
launched-artifact run may instantiate in different classloaders — so every recording is a **file**,
and every cursor is taken over that file.

### What the diagrams are, and the four rules that keep them stable

The network section is **observed, never narrated**: `Interactions` records notes only.
`NetworkTaps.restAssured` (the framework ships it now — this repo's hand-copied
`StoryNetworkFilter` was deleted when the catalogue was written) taps what a story sends *into* this
service; the three recordings above supply what it sent *out*; `StoryDaemon` instruments the control
socket by hand, because the framework ships no socket tap and a frame is not a request.

1. **Order is load-bearing and the package names carry it.** `UserflowClassOrderer` sorts by
   fully-qualified class name, so `…workspaces.api` drains before `…workspaces.stories.*` and the
   boot story owns the startup traffic; within `stories`, `creation` < `editor` < `operations` <
   `refusals`. `@UserflowRunsAfter` states the ones that are real dependencies as
   well — `editor` runs after `creation` because the containers-client token the editor's container
   PUT reuses is first minted there.
2. **A cached fetch belongs to whichever story paid for it.** quarkus-oidc-client caches its mint
   for an hour (`StoryPeers` answers `expires_in: 3600` on purpose), so `POST /idp/token` lands in
   the first story that needs the one named client `qits` (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4) —
   today the workspace provision — and in no story after. Running one class alone inherits the arrow
   and fails its own edge count, loudly, which is the right way for that assumption to break.
3. **An id that reaches a label inside a segment has to be AUTHORED.** `Labels` rewrites whole
   segments it can tell were generated, so a uuid row id scrubs to `{id}` in
   `/projects/api/repositories/{id}` — but the workspace fixture's id also travels *inside* a
   container name (`qits-ws-<label>-<repoId[0:8]>`), where eight hex characters are not a whole
   segment and are not rewritten. That fixture's id is a literal for exactly that reason.
4. **Asynchronous far-side traffic is awaited before the story returns.** The commission reconcile
   runs from a `StartupEvent` observer on its own thread; the boot story polls `StoryPeers`'
   recording for its listing rather than hoping. An edge that lands after the drain is an edge in
   the next story's diagram. **Repeating** background traffic is the same problem with no end: the
   event-stream redial and the catch-up sweep (qits-1153's `AgentWaitListener`) would land in
   random stories at their shipped intervals, so `StoryProfile` sets both to an hour and the boot
   story owns the one call of each.

**`assertEdgeCount` is what every class ends with**, because a count is how an absence is asserted
when the peer itself was legitimately reached — "the refused caller never reached the git host" is
one edge in and none out, and no presence check can say that.

### What is deliberately not covered, and why

- **The bootstrap chain.** `qits.bootstrap.autorun-enabled` is `false` in the profile: its observer
  is async, and the bootstrap await holds the technical process open for its whole chain timeout —
  which is what the workspace story polls to learn the provision is over. It is covered by the
  `@QuarkusTest` suites and by the docker-backed `Daemon*IT`, where a real daemon really runs it.
- **The reconcile's sweep.** Its listing is covered (the boot story); the listing is empty, so no
  credential is ever given back.
- **The container-to-idp credential exchange.** The workspace story reads the commissioned pair out
  of the workload spec — the only place it exists, and exactly where a container finds it — and then
  mints its dial bearer directly, because the stub idp answers an opaque string no gate could
  validate. That the pair travelled is proved; that it can be exchanged is qits-platform-idp's own
  claim.
- **The git host's protection hook and its authorization.** `StoryGitHost` exports what it serves
  unconditionally. Who may push a protected ref is qits-githost-service's suite's question.
- **A landing losing a race.** The lease serialises this flow's own integrates, and staging a
  writer *outside* the flow needs a hook on the far side of a socket from the launched process. It
  is a `@QuarkusTest`'s, with `FakeGitHostAddress.beforeNextPush`.

Every story is **browserless** (an `Interactions` parameter and no `Flow`), so the framework's
transitive Playwright never launches anything and no Chromium is needed to build this module. The
class orderer is installed the one way Quarkus permits — the
`junit.quarkus.orderer.secondary-orderer` line in `service`'s test properties; a local
`junit-platform.properties` hard-fails surefire.

The composed QA pipeline's verify publishes the reports as the docs bundle
`@userflows/qits-workspaces` — once per release-request fold, since per-push CI retired. It **gates**,
like every step of the composed pipeline: a step that did not gate would make the run a verdict
qits-projects' gate matches neither arm of, so there is no way to declare one — qits-ci refuses the
key outright — and a red story holds the fold.
