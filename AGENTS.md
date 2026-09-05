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

The SPA is the sole submodule and an image build needs it.

    git submodule update --init

`service/src/main/webui/` is initialised explicitly by the pipeline. qits-eventstream is a normal
Maven dependency and must not return as a gitlink or reactor module.

The one thing beyond `.sdkmanrc`'s GraalVM a build wants is **node on `PATH`** — Quinoa shells out
to npm for the Angular client. `mvn verify` does not need it (Quinoa is disabled by default in
tests), `./mvnw package` does, and it is deliberately the machine's OWN node: nothing in
`application.properties` asks Quinoa to install one, so no build silently downloads a toolchain.
Only `docker/Dockerfile`'s Mandrel stage, which has no node at all, passes the install flags.

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
property still reaches a binary's runtime config, which is the only reason `ServiceProxyRoute` may
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
  qits-eventstream for the causation persistence trio and nothing else out of that jar — see
  "The causation stamp on every push" for why the boundary reads *seams* rather than *the bus*.
- `service/` — `api` (JAX-RS + SSE, including the raw vertx routes), `daemonhost` (the control
  socket and registry), `bus` (what is left of the event-bus wiring: the push causation stamp),
  `wiring`, `security`.

There **was** a `workspaces-events/` module — `events` and nothing else, the vocabulary a consumer
depended on — holding `SCMRelease`. It went with the release door on 2026-09-03: qits-projects
publishes that event now and declares its own identical record, and no other repository ever
imported this jar (qits-ci keeps a local copy of its own). Do not reintroduce the module for an
event this service does not publish.

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
new entity that skips the decision fails the build naming the class. Two in, four out:

- **`Workspace`** — in. Both creation paths (`createWorkspace`, and `CaptureService.capture` behind
  the capture ingest) run on the request thread, so the `CausationStamp` listener reads the REST
  filter's restored scope. Nothing is set as data because nothing crosses an executor.
- **`WorkspaceEvent`** — in, and it is the one that carries the trace. The workspace row says why
  the unit of work exists; a MERGED/INTEGRATED entry answers to whatever asked for *that* landing.
  All six `recordEvent` sites are on the flow's own thread, including the ones a machine caller
  drives with a bearer of its own.
- **`ServiceEvent`** — out. `ServiceEventPersister` writes on supervisor/scheduler threads with no
  request context (that is what its `@ActivateRequestContext` is for), no scope stands there and no
  cause is in reach as data. The column would be null forever.
- **`BootstrapRun`** — out. An updatable singleton: one row per `(workspace, command)`, overwritten
  by every run. The stamp is insert-only, so it would pin the first run's cause while every column
  beside it moved on.
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

**The target is PostgreSQL 18.4** — the tag `components/qits-database/qits-database-oci` is built
from, and the version the suites' embedded binaries are, so a migration is proved against the engine it ships on.
Two H2 habits are gone with it: a rule that applies to some rows is a **partial unique index** now
(`create unique index … where …`, which is what `uq_workspace_active_branch` finally is, instead of
the generated column H2 forced), and `clob`/`blob` are `text`/`bytea`. The second is also an entity
rule — see below.

**`@Lob` is banned on this context's entities.** On H2 a `@Lob String` was a clob and the two agreed;
on postgres `@Lob` means a LARGE OBJECT, so Hibernate binds an oid and the insert fails against the
column the migration declares. Spell it `@Column(columnDefinition = "text")` — or `"bytea"` for
bytes — as `Workspace.preamble`, `WorkspacePromptDraft.content`, `ServiceEvent.logExcerpt` and
`WorkspacePromptAttachment.bytes` do.

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

**Four writes are wrapped, and they were chosen for what a lost one costs**, not for being writes:

- **`WorkspacePromptDraftService.saveDraft`** — the autosave, on a debounce while someone types. It
  is also the perfect first adopter: one `insert … on conflict`, idempotent by construction, which
  is why `WorkspacePromptDraft` is `@Uncaused` in the first place.
- **`WorkspacePromptDraftService.deleteDraft`** — delete-if-present, twice, in one transaction
  deliberately: a draft kept with its images gone is a broken composition.
- **`WorkspacePromptAttachmentService.addAttachment`** — a paste, whose bytes nothing else holds a
  copy of. The row id is minted **before** the retried unit so every attempt writes the same primary
  key; generated inside, a retry would be a second row.
- **`BootstrapRunService.recordOutcome`** — bookkeeping after the step already ran in the container,
  and the caller swallows failures by design, so a dropped row is silent. Its thread is the
  registry's single `daemon-sink-dispatch`, never a socket thread or a monitor: a held attempt
  delays the outcomes queued behind it and loses none.

**What is deliberately not wrapped**, beyond the read-seam rules above (which all still hold — stay
outside `WorkspaceService`'s `synchronized` methods, and never split a multi-op bracket):

- **The workflow verbs** — `createWorkspace`, `merge*`, `integrateWorkspace`, `cleanupBranch`,
  `discardWorkspace`. They orchestrate pushes and containers, so their bodies are not database-only
  and re-running one is not a re-run of a write.
- **`ServiceEventService.publish`** and the other fail-soft diagnostics: dropping one is the
  designed behaviour.
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

`LegacyDaemonControlSocket` serves the old label path for containers provisioned before the move —
their `QITS_WORKSPACE_DAEMON_URL` was injected at creation and only a recreate re-injects it. It
resolves the label and **refuses when more than one active workspace carries it**, which is the
collision the id exists to remove. Its path deliberately keeps no `/workspaces` segment: the address
is not ours to pick, it is whatever is already baked into a running container. Delete it once no
such container can still be running.

## Where this service answers

Two surfaces on one host. The **client is at `/`** — this service has a host of its own,
`workspaces.<env>.<domain>`, and the SPA is what it answers with. Every **machine** route is
`/workspaces/<second level>/…`, always: the edge routes **verbatim by prefix**, so the service serves
the prefixed path itself, on its own host and on every other one; there is no unprefixed form, on the
edge or on `qits-net`. `README.md` has the table.

The one thing to know before you add a route: **`quarkus.rest.path` moves the JAX-RS routes and
nothing else.** A raw Vert.x route or a `@WebSocket` path registers straight onto the router with a
literal and must carry `/workspaces` itself. Five do, each for its own reason (`EditorProxyRoute` is
the sixth raw route and carries no path at all — see below):

- `DaemonControlSocket` — `/workspaces/daemon/{id}`, and a **cross-repo contract**:
  `WorkspaceContainerFactory` injects `ws://<host>:<port>/workspaces/daemon/<id>` as
  `QITS_WORKSPACE_DAEMON_URL` and qits-workspace-daemon dials exactly that. Change both together.
  A commissioned daemon exchanges its own client pair for a qits-workspaces-audience bearer and
  presents it on every upgrade; the endpoint requires `qits:system`. The local/no-IdP topology
  stays anonymous only while the machine-auth rollout gate is off. Do not make this path public to
  repair a failed dial-home: a missing bearer is an integration failure, not a routing exception.
- `ServiceProxyRoute` — `ServiceProxyPath.PREFIX`, `/workspaces/service/`, which is also baked into
  the dev server's `QITS_PUBLIC_BASE` at spawn, so the two cannot be changed apart.
- `ContainerProxyRoute` — `ContainerProxyPath.PREFIX`, `/workspaces/container/`, **the only path by
  which anything reaches a workspace-daemon's HTTP API.** `container` and not `daemon` because the
  control socket already owns that segment, and that literal is the one hardest to change (it is
  baked into every running container). Not a gateway route, and there must never be a `DAEMON`
  constant in the gateway's `QitsService`: a daemon is one process per container with no stable
  address to configure, and this service owns the row and the lifecycle.
  **It rewrites no path**: the daemon receives `/workspaces/container/{id}/files`, not `/files`, and
  is told that prefix is its own address via `QITS_WORKSPACE_DAEMON_API_BASE_PATH`
  (`ContainerProxyPath.base`, injected by `WorkspaceContainerFactory`) — the same arrangement
  `ServiceProxyRoute` has with a dev server's `QITS_PUBLIC_BASE`. A hop that rewrites a path leaves
  the two ends disagreeing about the destination's address; do not add a `substring` here.
  **A WebSocket upgrade does not go through `vertx-http-proxy` at all** — `proxyUpgrade` does it by
  hand. Two reasons, both read out of 4.5.26. The library skips its whole interceptor chain on an
  upgrade, so the bearer never reached the daemon and both interactive sockets answered 401 (the
  same defect the gateway works around in `EdgeHeaders.applyToUpgrade`). And the pipe it then builds
  is bare `a.handler(b::write)` installs with **no `writeQueueFull`, no `pause`, no `drainHandler`**
  and a failure arm that prints `"Handle this case"` and a stack trace — so a chatty dev server on a
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
  is an unbounded heap buffer. `request.toWebSocket()` gives a real `WriteStream`.
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

**`quarkus.quinoa.ignored-path-prefixes` is what keeps the SPA fallback off all of that, and it is
now one entry: `/workspaces`.** The values are **absolute** — Quinoa strips `ui-root-path` before
matching, and stripping `/` leaves the path as it is — so a prefix match on `/workspaces` covers the
JAX-RS routes, `/workspaces/q` and all four literals above at once. They used to be **relative**
(`/api`, never `/workspaces/api`), because the client was rooted at the segment; that is the one line
of this paragraph that inverted with the move to a host of its own, and an entry left in the old
spelling matches nothing and looks exactly like an unset key.

The key cannot go back to being unset. Quinoa derives its skip list from `quarkus.rest.path` and
`quarkus.http.non-application-root-path` only, and with the client at `/` the fallback sees every
path this service does not otherwise serve. Measured on the packaged fast-jar before the key was set,
`GET /workspaces/daemon`, `/workspaces/daemon/` and `/workspaces/daemon/{id}` — a plain GET, since
websockets-next claims only the upgrade — each answered **200 `text/html`** with the SPA's
`index.html`. A machine client parses a web page as data; the correct answer is a 404. Add a route
under `/workspaces` and there is nothing to do here; add a **root-level** one and add its prefix in
the same commit. `application.properties` carries the reasoning.

**The editor is the one surface this key could never protect, and it is protected by ROUTE ORDER
instead.** `EditorProxyRoute` is matched on the forwarded host and claims *every* path under it —
`/`, `/static/…`, `/stable-<commit>/…` — because openvscode-server serves from `/`, so there is no
prefix to list. What keeps it off `index.html` is that it runs first and then never falls through:
registered at order **1000**, ahead of the built client's static files (**1060**,
`GeneratedStaticResourcesProcessor`) and of Quinoa's SPA-routing fallback (**40000**,
`QuinoaProcessor.runtimeInit`) — both read out of the 3.34.6 / 2.8.2 bytecode on 2026-08-31, not
measured against a packaged jar, because a Quinoa build needs an npm registry this workspace cannot
reach. Once the route recognises an editor origin every answer it gives is an answer: the 404 and the
splash included, `rc.next()` is never called. It sits deliberately **behind** the application's own
routes, which take Vert.x's auto-sequence from 0, so the machine surface keeps its paths on every
host. A second root-level catch-all added here has to think about all three numbers.

**A workspace is not a sub-resource of a repository.** This context holds a repository id as a
string, in another database, with no FK — so collections filter by `?repositoryId=` and an item is
`{id}` alone. `/branches/{merge,cleanup,resolution}` take `?repositoryId=` too: a branch has no id of its own,
so the repository narrows and the body names the branch. `history/{id}` carries no repository at
all; it was decoration on the item routes and a real filter only on the collection.

**Two host surfaces were deleted rather than moved**: `/workspaces/{id}/services…` and
`/workspaces/{id}/bootstrap-commands…`. Both ran inside the container with the host forwarding, and
the daemon's own `ServiceSupervisor`/`BootstrapRunner` do the work. Everything host-side behind them
stays — `ServiceSupervisor`, `WorkspaceBootstrapRunner`, both driver ports, the
`StartService`/`SignalService`/`RunBootstrap` events, `service_event` and its SSE feed,
`workspace_bootstrap_run`, `BootstrapRunService`, `ServiceProxyRoute` — because the
provision → bootstrap → services sequence is host-orchestrated and is not REST-driven. What went is
the addressability, not the capability. **Both are now re-exposed on the daemon's own `WorkspaceApi`**
(`GET /services`, `POST /services/{name}/{start,signal}`, `GET /bootstrap-commands`, `POST
/bootstrap-commands/[{name}/]run`) and reachable through `ContainerProxyRoute` — do not add host
routes back — that is `migration-plan.md` §9 item 16, now closed.

**`workspace_bootstrap_run` spent a release with no reader and now has one**:
`GET /workspaces/api/workspaces/{id}/bootstrap-runs` (`WorkspaceBootstrapRunController`). That is
not the deleted controller returning — it reads a **host** table, while the run verbs stay on the
daemon. Host-owned state gets a host route; nothing forwards. The client joins it against the
daemon's declared `GET /bootstrap-commands` on `bootstrapCommandId`. `BootstrapRun`'s javadoc
carries the full note.

Three more host-owned surfaces landed on the same rule, all plain JAX-RS under `qits.rest.path`:

- `GET /workspaces/api/workspaces/{id}` — the single-workspace read. The same `WorkspaceDto` the
  collection serves, so a detail page opens from a bare id; ACTIVE only, 404 for a resolved one
  (whose live half would be uniformly null — `history/{id}` is where that record stays readable).
- `GET`/`PUT`/`DELETE /workspaces/api/workspaces/{id}/prompt-draft` — the composition the next agent
  run is written in. Database rows, no container, so they work while the workspace is STOPPED.
- `GET`/`POST`/`DELETE /workspaces/api/workspaces/{id}/prompt-attachments[/{attachmentId}]` — its
  images, on their own SSE topic so a debounced text autosave does not re-download every picture.

Still keyed on the label, deliberately: `service_event.workspace_id` — diagnostic history that
outlives the row, see `V2`'s header.

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

## The vendored protocol module

`workspace-daemon-protocol/` is a copy of the daemon repo's module, same java package, different
artifactId. Any change to it must be mirrored in
[qits-workspace-daemon](https://github.com/QuicklyIterateTheSoftware/qits-workspace-daemon) and bump
`DaemonProtocol.CAPABILITY_VERSION`. `DaemonCodecTest` runs on both sides and is what catches drift.

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

**Only one push option is left, and it is not a release's.** `-o qits.release` went with the release
door — the git host's protection hook guards the default branch alone, and nothing here writes one —
and so did the `-o qits.no-ci` that quieted a release's trunk push. What survives is
`RepoMirror.createBranch`'s own `-o qits.no-ci`: a branch create points at a commit the host already
built, so a build for it would be a redundant run per created branch (an aggregate workspace creates
one per registered submodule). The mirror is a **cache**: delete it and the next request re-clones.

## The release door left, and what stayed

**Until 2026-09-03 this service released.** `POST /workspaces/api/workspaces/{id}/release` and
`POST /workspaces/api/branches/{release,execute-release}` merged a branch into the repository's
default branch, stamped a CalVer `YYYY.MMDD.HHMMSS` version into the same index, committed both as
one `release(<version>): <summary>` commit, pushed it atomically with an annotated tag under
`-o qits.release`, published `SCMRelease`, and promoted the same sha onto an `environment/*` entry
branch. **All of it is gone**, along with `Mode.RELEASE`, `VersionStamp`, `VersionBumper`,
`MavenVersionBumper`, `NpmVersionBumper`, `PomVersions`, `PackageJsonVersions`, `TextSplice`,
`DeploymentSpecReader`, `ReleaseAnnouncer`, `SCMReleaseAnnouncer`, the `workspaces-events` module
that held `SCMRelease`, and `qits.workspaces.release.entry-branch`.

**A release is a release request in qits-projects now**: it folds `main`, the named branches and
every released-but-unmerged tag onto a `release/<id>` branch through qits-githost's in-core git
primitives, the QA pipeline builds that fold, and a green gate stamps the manifests, tags the fold
and publishes `SCMRelease`. `main` is finalized after the deployment. The bump engine's logic was
ported to qits-projects (`PomVersions` there carries the note); the version-uniqueness guarantee is
now githost's refusal to overwrite a tag ref rather than an atomic branch+tag push.

**What stayed is the workspace feature.** `BranchIntegrator` — the renamed `ReleaseIntegrator`, with
every release arm removed — is the git half of landing one workspace branch on another: refresh the
mirror, preflight the merge in the object store, merge in a **detached worktree on the mirror**,
commit once, push. `WorkspaceService.landWorkspace`/`landOnBranch` wrap it with the guards, the
repository lease and the workspace's resolution.

**`POST /workspaces/api/workspaces/{id}/integrate`** lands a workspace on **its parent branch** — a
`task/…` on the `epic/…` it forked from — as one pushed commit `integrate(<source>): <summary>`. No
stamp, no bump, no push option, no tag, no event, no `version` in the response. A workspace whose
parent *is* the default branch is refused with `RELEASE_REQUIRED` naming the release flow, because
this service writes no default branch at all.

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
  qits-projects') and a main branch renamed between them leaves exactly one right. Nothing else here
  refuses to discard a main workspace; that hole is deliberately not inherited.
- **`doDiscard` gained a `deleteBranch` flag rather than a copy.** The ref is already gone, so the
  push would be a round trip whose only outcome is the failure that method's catch swallows — a
  stated no-op instead of a silent one, the reading `ensureContainer`'s branch-gone abandon already
  makes. The flag and not a second sequence because the ORDER is the part worth having one of
  (`fireStopping` before `rm` is pinned by `WorkspaceContainerStoppingRecorder`).
- **A dirty container is LOGGED, never refused.** This is the one place this service knowingly
  discards uncommitted work: there is nothing left to push to, and an immortal workspace on a deleted
  branch is worse. The *unpushed* half is not probed, because it cannot be — `isFullyPushed` compares
  the container's head against the branch's ref on the git host, which is exactly what the release
  deleted. It is deliberately **not** routed through `cleanupBranch`/`canCleanupBranch`/
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

**`merge` and `branches/merge` 409 with `RELEASE_REQUIRED` when the target resolves to the default
branch.** They keep every other target — merging into a *parent* branch is what stacked workspaces do
all day, which is also what `/integrate` does with a push and a lease behind it. **`merge` is not
redundant**: it still takes an arbitrary target and answers with conflicts rather than throwing, and
`branches/merge` needs no workspace at all.

One consequence, recorded because it is a real loss rather than an oversight: **a plain branch can no
longer be auto-cleaned up after integration.** A plain branch's cleanup parent is the main branch by
definition (`canCleanupBranch`), so it is eligible only once merged *into* that branch — and nothing
here merges into that branch. Workspace branches still resolve and are still deleted; that happens
inside `/integrate`.

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
`EventFrame`, no `QitsEventBus` anywhere in `domain`, and the announcement ports stay ports. The
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
`bus/EventWireReflection` all went with it. **The wire name is the class name**
(`QitsEvent.signature()` returns the simple class name), so qits-projects' own identical record is
the same event on the wire; nothing keying on the signature noticed the move.

What the file `.config/qits/ci-event-release.yml` in this repository does is unchanged: it selects
`SCMRelease` for **this** repository and builds the released tag. The publisher is somebody else's
now, the consumer is still ours.

**What is left of the bus here is the causation half** (above): `EventstreamPushCausation` reading
the ambient scope onto every push, and the eventstream jar's persistence trio on the entities. This
service publishes nothing and listens for nothing, and `qits.eventstream.enabled` stays off in
`%dev`/`%test` because the sweeper is the jar's.

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

## The web editor: one workspace, a second image, and an origin of its own

The editor is **not a new thing with a lifecycle**. It is the project wrapper's main workspace — the
per-project singleton `WorkspaceService.createMainWorkspace` already maintains — started from a
richer image and told to supervise `openvscode-server`. There is no editor row, no editor container
and no editor teardown, which is why the door's answer carries the *workspace* row id: the two ways
out of a stuck editor are `/workspaces/{id}/stop-container` and `/recreate-container`, the routes
that already existed.

**Which workspace is DERIVED and there is no column.** `WorkspacePostures.isWrapperMain` is
repository archetype `PROJECT` plus branch == that repository's main branch. A column would be a
fourth copy of an answer three places already hold and would go stale the day a main branch is
renamed. `RepositoryView` grew `archetype` for this and nothing else, and `ProjectsRepositories`
binds the one qits-projects field this context had deliberately left unbound.

**`isWrapperMain` is a `default` method**, and that is mechanical as well as semantic: every
hand-built test factory writes `WorkspacePostures` as a lambda, so a second abstract method would
break all of them at once. False is the right default — a port that does not answer is a plain
workspace.

**`PersistedWorkspacePostures` memoizes it, and that memo is load-bearing.** The answer picks the
image AND two environment variables, so it is part of the spec — and a spec that differs from what is
running is a `Recreate.ifChanged` **replacement**. A live `RepositoryLookup` call cannot promise the
same answer twice: an unreachable qits-projects throws, the factory reads that as "not the wrapper",
and the resume presents a plain-image spec and destroys the editor's container. The memo is sound
because its three inputs do not move (a workspace's branch is written once at creation, an archetype
is what a repository was registered as, a main branch is the ref it was cloned from), and it caches
both answers — a plain workspace re-deciding on every ensure is the same exposure pointed the other
way. Row ids are never reused, so an entry can only become dead weight.

**Only a FULLY-ANSWERED view may be written down**, and an unreachable registry is not the only way
to not learn the answer. `RepositoryView.archetype` and `.mainBranch` are both nullable, so a 200 can
arrive carrying neither — and `isWrapper()` on such a view is false. Memoizing *that* is the outage's
exposure with a status code in front of it, except permanent: one half-answered read and every ensure
for the life of the process describes the plain image, which under `Recreate.ifChanged` replaces the
editor's container with a plain one. So a view missing either field takes the same third answer a
thrown lookup does — false for this call, nothing remembered. An archetype the registry *did* state
and this host does not recognise is a real answer and is memoized as "not a wrapper".

**Two image keys, not a suffix.** `qits.editor.image-repo`/`-version` are a second pin because two
repositories publish the two images on two calvers: qits-workspace-daemon publishes `qits/workspace`
and qits-workspace-editor-oci follows it one release later. A derived name (`${image-repo}-editor`)
would tie the versions into one string and be wrong the moment either train ran alone. The version
half is a fallback the deployer overrides with `QITS_EDITOR_IMAGE_VERSION`, exactly as the workspace
pin does. **The shipped default names no released tag** — qits-workspace-editor-oci has never been
released — so it states the `qits/workspace` calver the editor recipe currently sits on, and a
wrapper-main workspace fails at the orchestrator's pull with `IMAGE_MISSING` until the first real
release moves it. That is the same posture the workspace pin shipped with, and the loud version of
the same absence.

**The editor environment is both-or-neither**, the rule the commissioned credential block follows:
`QITS_WORKSPACE_DAEMON_EDITOR_ENABLED` without `…_EDITOR_PORT` would name a supervisor with no port
to bind, and a port alone would name a listener nothing starts. `qits.editor.port` is where that
number is spelled once, and it equals the daemon's own default so a container launched before the key
existed answers where this expects it. **Nothing on the host side DIALS it** — the port is loopback
inside the container and the proxy asks the tunnel for the listener by name — so the only reader here
is `WorkspaceContainerFactory`, which writes it into the environment and nothing else. Every other workspace is
told **nothing** — silence is what the daemon reads as "no editor", so an explicitly-false pair would
be a second way of saying the same thing.

### The origin, and how a label reaches a workspace

`openvscode-server` serves from `/` with its own service worker, websockets and webviews, and this
platform rewrites no paths anywhere — so an editor is a whole host, `editor.<project>.<env>.<domain>`,
aliased at the edge onto this service. The project label arrives in `X-Forwarded-Host`.

`EditorHost` turns the **first entry** of that header into a label and stops; `EditorProxyTargets`
turns a label into a workspace row and nothing else. **Nothing about the request ever selects a host
or a port** — `DaemonProxyTargets`' posture verbatim, and for its reason. The label is validated
against qits-projects' own project-slug grammar before anything is looked up, and an unknown label is
empty, which the caller answers as a 404 with nothing dialled.

**A label reaches a repository by DERIVING a name.** There is no route from a project slug to a
project — the registry answers repositories by id and by `(projectId, name)`, and this context holds
no project table — but qits-projects names a wrapper `<slug>-<slug>` (`ProjectService.wrapperName`)
and the slug is `updatable = false`. So the label *recognises* the wrapper among the repositories
this service already has **root** workspaces for (`activeRootRepositoryIds`: ACTIVE, parent null,
distinct — one entry per repository somebody has opened a main workspace for).

**The registry half is remembered and the row half never is**, and the split is the whole design: a
project's wrapper repository cannot change, so recognising it once is recognising it for good and a
warm resolution is a map read plus one indexed query; a main workspace *can* be discarded and made
again, so a cached row id would point the proxy at nothing.

**A miss is remembered too, and against the CANDIDATE SET rather than a clock.** The scan behind one
is a `find` per root repository — N qits-projects round trips per request, and a browser sitting on
an editor origin whose main workspace does not exist yet reloads twice a second, so uncached that is
N calls twice a second per tab to keep saying 404. But a plain TTL would be wrong in the direction
that matters: the miss's answer is a **404 page and not the reloading splash**, so a project whose
main workspace was created a second ago must resolve *now*. It does, because everything the scan
reads about a repository is immutable and the candidate set is one indexed local query: the answer
can only have changed when the set has. `qits.editor.label-miss-ttl-ms` (5 s) sits underneath as a
backstop for the label nobody ever registers. A scan the registry **threw** in is not remembered at
all — "could not ask" is not "not there".

### The data path, and the five answers it can give

`EditorProxyRoute` is `ContainerProxyRoute`'s sibling and takes its hardened parts wholesale — the
hand-rolled `proxyUpgrade`/`openUpgrade`/`completeUpgrade`/`pipe`, `writeQueueFull`/`drainHandler` in
both directions, a refused handshake forwarded with the origin's own status, `DbRetry` at the lookup.
**Never route an upgrade through `vertx-http-proxy`**: it skips its interceptor chain (so the header
strip below would be dead on exactly the requests that carry a session) and its pipe has no flow
control at all. That measurement is in `ContainerProxyRoute.proxyUpgrade` and it did not stop being
true one origin over.

**The path is forwarded verbatim, and there was never anything to rewrite.** That is the whole
payoff of the editor being a host: openvscode-server serves from `/`, so a prefix would have had to
be stripped somewhere, and this platform strips nothing anywhere.

**The identity headers are required and then removed, and each half is wrong without the other.**
Required, because this route authenticates nothing and must not invent a boundary — the edge's
session gate is the boundary, and `X-Qits-*` is what says a request came through it. The edge strips
that namespace from every inbound request unconditionally, so its presence cannot be forged and its
*absence* is evidence too: a **403**, not an anonymous pass. 403 rather than 401 because this hop has
no challenge to issue; the login lives at the edge, which redirects a session-less browser long
before a request arrives here. Removed, because the editor runs an untrusted checkout with a shell —
a platform header it can read is one it can echo at something that trusts the namespace.
`Authorization` goes with the namespace and **nothing replaces it**: unlike the daemon, the editor is
not a peer this service authenticates to, so a bearer would be a credential moved inside the sandbox
for nobody's benefit.

**What is NOT stripped today is the platform session cookie**, and that is a known gap rather than a
decision made in this repo's favour. `EdgeRouter`'s cookie strip is guarded by `session == null`, so
a browser navigation to an editor vhost carries its cookie through (see the edge reading below). A
blanket `Cookie` strip would break the cookies the editor sets for itself, and a name-based one needs
the edge's cookie name, which is not this repository's to spell. Whoever writes the `qits.edge.apps`
entry should settle it there — that is where both facts live.

**Where the state comes from, and its three caching rules.** `WorkspaceDaemonRegistry` implements
`WorkspaceEditorState` off the daemon's `EditorState` frame — sent once per control-socket connect
and then once per transition — caching it per row id beside `gitClean`/`agentActivity`. A
**disconnect drops the entry**, because nothing is known then and that is not the same as the editor
having ended; **`ENDED` is kept**, because it is the one value that lets a splash stop waiting; and a
**state this host cannot name drops the entry** rather than keeping the last one, since the wire
carries a String precisely so a newer daemon can say something new, and a stale `RUNNING` outliving
the transition that contradicted it is the one direction that costs a reader a splash they should
have seen. Absence is "nothing reported" and never "no editor": a plain workspace, a container that
is down and a first frame that has not arrived are one answer, and they deserve the same one.

**Five answers, because a waiting editor is not a broken one.** `ServiceProxyRoute`'s splash pattern,
gated on `WorkspaceEditorState`:

- no container, or a stopped one → **200 and a self-refreshing page**. Opening the editor while it
  starts is the same act as opening it once it has.
- container up, editor `STARTING` **or nothing reported** → the same splash. A reader cannot act on
  the difference, and "no frame yet" and "starting" are one state to them.
- editor `ENDED` → a **502 of its own**, and deliberately not a splash: it is terminal, so a page
  that kept refreshing would spin for the container's lifetime.
- editor `RUNNING` and **no tunnel** → a second, distinct **502**, naming the daemon tunnel. Also not
  a splash: nothing this side does will make a tunnel appear.
- no such project → **404 with nothing dialled**.

**THE REVERSE TUNNEL IS THE ONLY WAY IN, and there is no direct dial to fall back to.** The daemon
binds openvscode-server to the container's **loopback**, so no address on `qits-net` reaches an
editor and `resolveTarget(container, editorPort)` names a port in another network namespace. This
route carried that fallback for one commit; in the shipped topology every request it took was a dial
and then a 502 — concretely, with `qits.workspace.daemon-tunnel.enabled=false` *every* editor request
was one — and the real cost was in the suite, where the header strip, the verbatim path, the bounded
pipe and the upgrade were all proved on the arm production never takes. So "RUNNING editor, no
tunnel" is the answer above, and `EditorTunnelRouteTest` is where the forwarding is proved. Nothing
in this route states a port any more; the listener is asked for by name.

**The tunnel is asked before the orchestrator, and that ordering is the performance decision that
matters.** A live control socket at the editor capability is stronger evidence that the container is
up than a status call is — `ContainerProxyRoute.resolve` records the same reasoning — and an editor
session is a stream of requests, so a round trip per request would cost more than the container. The
container read runs only where there is no tunnel, and it still earns its keep there: it is what
tells a stopped workspace's splash from the 502 above.

**The tunnel carries a TARGET now, and one tunnel serves one target.** `OpenStream` names
`StreamTarget.EDITOR`; the daemon resolves the name against its own allow-list, so the host still
never states a port inside a container. `WorkspaceTunnels` keys its `NetServer`/`HttpClient` pair on
`(workspace, target)` and never shares one across targets — the ephemeral-port hazard the class
documents, pointed inward: a listener is chosen once, when a socket is accepted, so a shared port
would make the pooled connection behind a keep-alive an editor stream or an API stream depending on
which request opened it first. The **capability gate is per target** for the complementary reason: an
older daemon decodes an absent target as `API`, so a name it has never heard of would be served by
the wrong listener rather than refused, and an editor answering the daemon's 404s reads to a browser
as broken rather than absent. `EDITOR_CAPABILITY_VERSION` (5) lives in `WorkspaceTunnels` and not in
`DaemonProtocol` only because that module is a byte-identical source copy; move it the day the daemon
repo declares one.

**That gate is read TWICE, and the second reading is the one a race needs.** `originFor` checks the
capability once per resolution and hands back a listening port; `onAccepted` mints the nonce and
sends the `OpenStream` one accepted connection later. A daemon that reconnected on an older image
between the two would be sent a target its codec drops — and an absent target decodes as `API`, so
the stream would land on the daemon's own API port instead of being refused. Bounded (that port wants
a bearer this side does not send) and still wrong: a browser reads someone else's 401s as its editor.
So `onAccepted` re-reads the registry and closes the socket when the target no longer suffices, which
is the same connection error the nonce's own expiry would give a few seconds later.

**The keepalive fires in three places, and the third is the one that makes it true.** Per request,
per opened stream, and **per frame from the browser** through the pipe. An open tab that reads a file
for an hour sends nothing over HTTP and everything over that socket, so a keepalive that only fired
per request would let the sweep stop a container somebody is looking at. It is affordable because
`EditorKeepalive` debounces (and is a no-op entirely while the idle-stop switch is unset, which is
how it ships) — the class was written for exactly this call site.

**`Host` is not rewritten and the editor sees the origin's authority**, on both transports, so it
cannot tell which one a request took. What says the public name is `X-Forwarded-Host`, forwarded
untouched. Nothing in openvscode-server reads `Host` today; if that changes, the header to hand it is
already on the request.

### The door

`POST /workspaces/api/editor/ensure?repositoryId=<wrapper>` with an empty body, answering a **bare**
JSON object `{workspaceId, containerStatus, editorState, editorReady}` — 201 fresh, 200 existing, the
`TerminalController.open` pairing. It is the whole readiness protocol: there is no status read beside
it, so a caller polls this and nothing else and a reader who reloads mid-start rejoins the editor
already coming up. The body is bare rather than enveloped because a two-second poll reads four
scalars off it; the `WorkspaceDto` routes keep their envelope.

**`editorReady` is the service's judgement, not the caller's**: the container is running *and* the
daemon says the editor is serving. A client that waited on `editorState` alone would be deciding for
itself when the editor answers requests.

**No locks, and none are needed.** `createMainWorkspace` is idempotent on the branch,
`uq_workspace_active_branch` makes that true under a race, and the orchestrator's ensure is a PUT per
place. What the door adds is not a lock but two reasons **not to ask**: a technical process already
running for the workspace *is* the start this call would make, and a container that is up with a
daemon on its socket is up. Without them a two-second poll would spawn one provision per tick through
a multi-gigabyte pull. A RUNNING row whose daemon is *not* live is asked about anyway — that is what
a container which died out-of-band looks like, and the ensure ladder's first rung is to find out.

A repository that is not a wrapper is a **400 naming the rule**, not a plain workspace start: an
ordinary workspace runs the plain image, so no editor could ever report and the caller would poll a
workspace that can never become ready.

### The idle-stop switch, and the keepalive

`qits.editor.idle-stop-after` (**blank as shipped**) gives the editor's container an `IDLE_STOP`
lifetime instead of `EXPLICIT`, so qits-containers' existing `IdleSweep` stops it. It applies to that
one workspace deliberately: a workspace is somewhere a person works and an agent runs unattended, so
nothing but a person may end one, while the editor's workspace is the one that is opened, read and
left.

**Stopping is not losing, and the reopen path is code that already exists.** The sweep *stops* a
place: the container keeps its id and its writable layer, `/workspace` is a volume of its own, and
the ensure ladder's second rung starts it back up where it stands — the daemon then finds a populated
checkout and clones nothing.

**It rides the POLICY, not the spec.** `Recreate.ifChanged` compares the spec, and a policy is not
part of one, so turning the switch on does not replace a running container.

`EditorKeepalive.touched(rowId)` is the entrypoint both sources call — the editor proxy route (per
request, per opened stream, and **per frame from the browser**, which is the one that covers a tab
left open on a file for an hour), and `WorkspaceDaemonRegistry.onAgentActivity` for an agent working
unattended (an editor that closed on a running agent would kill work in progress). Three things about
it are deliberate:

- **The debounce is not an optimisation.** A keystroke is a websocket frame, so one keepalive per
  request would cost more than the container it keeps. `qits.editor.touch-interval` (30 s) bounds it,
  and it **must stay well under the deadline** — the sweep measures time since the last touch, so an
  interval near the deadline would let a busy editor be stopped between two touches.
- **The claim is a `compute` and not a get-then-put.** Frames arrive on several threads; a
  read-then-write would let two of them see the same stale timestamp and both touch.
- **The claim is inline and the call is not.** Both callers are on threads that must not block — an
  event loop and a socket thread — so the cheap atomic part runs there and the database read plus the
  HTTP call go to one `editor-keepalive` thread. One thread is plenty: the debounce bounds the
  arrival rate, not the executor.

`ContainerRuntime.touch` is best-effort and never throws, for the reason `stop` and `rm` are: a
missed keepalive costs at worst a sweep, and a swept container is started back up in place.

### What the edge does with the editor's vhost (read 2026-08-31, not run)

A `qits.edge.apps` entry receives **full browser-session treatment**, which is what the editor needs
and what registry/mirror vhosts deliberately do not get. `EdgeRouter.handle:295` sends every service
target through `serviceGate`, which looks up the session cookie whenever sessions are on and the
request carries no `Authorization` (`:424-432`) and proxies with that session (`:446`).
`EdgeRouter.proxy:598` calls `EdgeHeaders.applyIdentity`, which strips the reserved `X-Qits-*`
namespace and writes `X-Qits-User`/`-User-Id`/`-Roles` (`EdgeHeaders:114-129`).

**The cookie is not stripped for such a request**, and the reason is worth knowing because it is not a
per-vhost rule at all: the strip at `EdgeRouter:599-604` is guarded by `session == null` alone.
Registry and mirror lose their cookie only because they arrive with an `Authorization` header or under
`anonymous-read-apps` and therefore reach `proxy(…, null)`. A browser navigation with no session is
refused into the login redirect rather than a 401 (`refuseService:521-531`), and an upgrade takes the
same gate and the same `proxy`, so a websocket carries the identity too (`:598` runs before the
branch at `:605`).

One thing to check when the alias is written: the edge matches `$app.$env.$domain` at positions 0 and
1 (`HostEnvironments.route:199-217`), so `editor.<slug>.<env>.<domain>` falls through to the
`$app.$domain` reading and lands on the `editor` app in the **default** environment. That is the
intended shape — one upstream for every project, told apart by the forwarded host — but it means the
environment label is not read out of the name, and `X-Forwarded-Host` is `set`-if-absent at the edge
(`EdgeHeaders.applyForwarded:193-200`), so a client-supplied value wins. Both are why the resolver
treats the header as caller-shaped input that selects a row and never an address.

## The credential a workspace container holds

A workspace container gets an **idp client of its own** — commissioned at provision, injected as
`QITS_COMMISSIONED_CLIENT_ID`/`QITS_COMMISSIONED_CLIENT_SECRET`, handed back at teardown. README has
the operator's half; here is what the code decides and why.

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

**Absent is a supported configuration in two spellings and they behave identically**: no
implementation of `CredentialCommissioner`, or one wired against no issuer. The switch is
`quarkus.oidc-client.client-enabled` — the extension's own, read a third time here for the reason
`ContainersClientProducer` reads it a second time. There is no key of ours, and there must not be.

## Admin workspaces: the one privilege a workspace can be granted

An **admin workspace** is an ordinary workspace whose container holds the **host's docker socket**,
so that platform administration can be done from inside a workspace. It is asked for at creation —
`POST /workspaces/api/workspaces` with `admin: true`, which the workspaces SPA offers as a checkbox
on the ad-hoc create form — and it is the only thing about a workspace container that is not the
same for every workspace.

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
  whatever runs next.
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
| `api.TokenValidationBootstrapIT` | authentication | the boot: the JWKS fetch, the commission reconcile, and a bearer cut for another audience |
| `stories.creation.WorkspaceProvisionIT` | workspaces | a workspace provisioned end to end, daemon dial included |
| `stories.editor.EditorEnsureIT` | editor | the editor door — the wrapper's main workspace begun, with a branch check but no push |
| `stories.operations.OperatorReadsIT` | operations | what a live read costs, and what a stored read does not |
| `stories.refusals.MergeDoorRefusalIT` | refusals | four ways not to get through a door, told at `/branches/merge` |

**Every override the profile sets is a RUNTIME key**, including the two that only look like
environment (`QITS_RESOURCE_DB_*`, `QITS_RESOURCE_EVENTSTREAM_*` — spelled as the deployer spells
them, so the shipped `${…}` expressions stay under test). A packaged process cannot be handed a
build-time key; it would silently take the default, which is this repo's own worst bug class.

**It is opted in by NAME, not by `skipITs`.** The root pom keeps `skipITs=true`, because failsafe
has one run per module and flipping it would turn the five docker-backed `Daemon*IT` back on with
it. Run it — and the userflow half of `.config/qits/ci-event-release-request.yml` runs it — as a
comma list:

    ./mvnw verify -DskipITs=false -Dquarkus.quinoa=false \
      -Dit.test=TokenValidationBootstrapIT,WorkspaceProvisionIT,EditorEnsureIT,OperatorReadsIT,MergeDoorRefusalIT

Adding a story class means adding it there **and** in the ci file. `-Dit.test` alone still runs the
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
   the first story that needs each of the three named clients — today the workspace provision for
   all of them — and in no story after. Running one class alone inherits the arrow and fails
   its own edge count, loudly, which is the right way for that assumption to break.
3. **An id that reaches a label inside a segment has to be AUTHORED.** `Labels` rewrites whole
   segments it can tell were generated, so a uuid row id scrubs to `{id}` in
   `/projects/api/repositories/{id}` — but the workspace fixture's id also travels *inside* a
   container name (`qits-ws-<label>-<repoId[0:8]>`), where eight hex characters are not a whole
   segment and are not rewritten. That fixture's id is a literal for exactly that reason.
4. **Asynchronous far-side traffic is awaited before the story returns.** The commission reconcile
   runs from a `StartupEvent` observer on its own thread; the boot story polls `StoryPeers`'
   recording for its listing rather than hoping. An edge that lands after the drain is an edge in
   the next story's diagram.

**`assertEdgeCount` is what every class ends with**, because a count is how an absence is asserted
when the peer itself was legitimately reached — "the refused caller never reached the git host" is
one edge in and none out, and no presence check can say that.

### What is deliberately not covered, and why

- **The bootstrap chain and the dev-server autostart.** `qits.bootstrap.autorun-enabled` and
  `qits.services.autostart-enabled` are both `false` in the profile: both observers are async, the
  config read would draw an arrow in whichever diagram happened to be open, and the bootstrap await
  holds the technical process open for its whole chain timeout — which is what the workspace story
  polls to learn the provision is over. Both are covered by the `@QuarkusTest` suites and by the
  docker-backed `Daemon*IT`, where a real daemon really runs them.
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

The userflow half of `.config/qits/ci-event-release-request.yml` publishes the reports as the docs
bundle `@userflows/qits-workspaces` — once per release-request fold, since per-push CI retired — and
is **non-gating by design**: it declares `gating: false`, so a red story shows the run red without
holding the fold at qits-projects' release gate.
