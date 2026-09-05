# qits-workspaces-service

The **host side** of qits workspaces: the workspace entity and its lifecycle, container
orchestration, host-side git through a mirror of each repository, the workspace-daemon registry, dev-server
supervision, the bootstrap-chain runner, the technical-process framework, prompt composition,
feature capture, and the routes over all of it — the machine surface at `/workspaces`, its segment,
and the client at the root of `workspaces.<env>.<domain>`.

A workspace is a branch of a repository **plus** a per-workspace container that clones that branch
into `/workspace`. This service creates, merges and deletes that branch the same way anything else
does: by pushing to the git host. It does **not** release one — a release is a release request in
qits-projects, which folds the sources through qits-githost and lands a tag. This repo owns everything about that from the host's side of
the boundary. Everything that runs *inside* the container belongs to
[qits-workspace-daemon](https://github.com/QuicklyIterateTheSoftware/qits-workspace-daemon).

    mvn verify        # resolves qits-eventstream 1.0.0 from local qits-artifacts

## Layout

| Module | What |
|---|---|
| `gitmirror/` | `eu.wohlben.qits.workspaces.gitmirror` — the git substrate: a local mirror per repository, the worktrees a merge runs in, and the pushes that are the only way a ref moves. Framework-free; its tests run offline against throwaway bares. |
| `domain/` | `eu.wohlben.qits.workspaces.*` — entity, persistence, dto, mapper, control, and the framework-free SPIs the daemon implements. No web, no JAX-RS. |
| `service/` | `eu.wohlben.qits.workspaces.{api,daemonhost}` — JAX-RS routes, the SSE channels, and the daemon control socket + registry. |
| `workspace-daemon-protocol/` | A **vendored copy** of the daemon wire contract. See that module's pom for why. |
| `service/src/main/webui/` | The SPA — a **submodule**, [qits-workspaces-frontend](https://github.com/QuicklyIterateTheSoftware/qits-workspaces-frontend). Quinoa builds it into the artifact and serves it at `/`. |

So a checkout needs one command a plain clone does not give you:

    git submodule update --init

Skip it and the web build stops at `No package.json found in Web UI directory` — `git clone` materialises
a gitlink as an empty directory, and that is the one state Quinoa treats as a misconfiguration rather
than as "no UI here". A checkout with the `webui` directory *absent* builds fine, with a warning.

Quinoa shells out to `npm`, so a build here also wants **node on `PATH`** — the machine's own, which
is the point: nothing in `application.properties` asks Quinoa to download one, so `./mvnw package`
never fetches a node tarball behind your back. The one environment with no node is the Mandrel
builder stage in `docker/Dockerfile`, and that is where the two
`-Dquarkus.quinoa.package-manager-install…` flags live, on the command line and pinned to a version.

`domain/` is a library jar. **`service/` is the application** — it carries
`<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as a native binary.
`domain` owns its **own datasource, persistence unit and Flyway lineage**
(`db/workspaces/migration`, its own PostgreSQL database), which is what makes this a standalone
deployable rather than a checkout of the monorepo.

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar

    ./mvnw package -Dnative
    ./service/target/qits-workspaces

**Both of those run commands deliberately fail today**, identically, and the message tells you why:
this service has no `RepositoryLookup`. See "Deploying it" below — it is the one thing between here
and a running process, and it is code rather than configuration.

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain — the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
if it finds none it does not fail, it quietly falls back to pulling a 1.8 GB Mandrel image and
compiling under docker. That fallback still works and is what a GraalVM-less CI gets; it is just not
the intended path, and it is worth recognising by name when a build that normally takes a minute
starts downloading a container image.

Because this service cannot boot unwired, "does the binary work" is answered by comparing it against
the fast-jar rather than by a smoke request: both reach the same `RepositoryLookup` failure, through
the same Flyway migration and the same startup event, and any *other* difference between the two is
a native-image defect. Three were found that way and are recorded in `AGENTS.md`.

## The boundary

Workspaces reference repositories **by string id, never a foreign key** — the two live in different
databases. Everything this context needs from the rest of qits goes through a port it declares and
the consuming application implements:

| Port | Required? | Absent means |
|---|---|---|
| `RepositoryLookup` | **yes** | won't start — an app without it is misconfigured |
| `RepositoryAddressResolver` | no | `RepositoryLookup` supplies the ordinary project-scoped git address |
| `WorkspaceCommandHistory` | no | a workspace's history shows no commands |
| `AgentSessionReporter` | no | `SessionStart` lineage is not forwarded |
| `WorkspaceTerminalSessions` | no | the interactive service terminal refuses the upgrade; the live log is unaffected |
| `WorkspaceChatInbox` | no | service events are spooled instead of delivered — the same path as "no chat is running" |
| `WorkspaceProcessTracker` | *implemented here* | `TechnicalProcessRegistry` is the default; the port stays so an application can substitute its own |
| `CredentialCommissioner` | no | no container is given a platform credential — today's behaviour |

One port points the **other way**: `LogLineClassifier` (with `LogSeverity`) is *implemented* here
and consumed by the command context's log persister, so a workspace's `?severity=` filter and the
LOG_LEVEL observer agree on what an error is. An application running both registers this
implementation there. `CommandOutputSink` is the same idea in miniature — a shape handed out, not a
service called.

In the other direction this context publishes `WorkspaceResolved` when a workspace is integrated or
abandoned. Because the delete is **soft**, no FK cascade ever fires for rows other contexts hang off
a workspace — that event is how they clean up, and it is fired synchronously inside the resolving
transaction.

## Deploying it

**It needs to be told where qits-projects is.** `RepositoryLookup` is a mandatory `@Inject` — a
workspace is a branch of a repository, and this context holds no foreign key into the repositories
tables, so the two facts it needs (does this repository exist, what is its main branch) come over
HTTP from the service that owns them. `wiring/HttpRepositoryLookup` is that implementation. It reads
one key, `qits.projects.url` — scheme, host and port, no path — and **refuses to start without it**
in a production launch, because a service that comes up answering 404 to every repository-scoped
route is a misconfiguration wearing the costume of an empty system. Dev and test downgrade that to a
warning so `quarkus:dev` and the suite stay runnable.

    # containers on qits-net
    QITS_PROJECTS_URL=http://qits-projects:8080

For a local run, put it in a file instead of remembering a flag:

    cp .env.example .env
    ./mvnw package -Dnative
    ./service/target/qits-workspaces          # no flags: :8091, registry on :8090

`.env` is gitignored; the `.env.example` beside it is tracked and is the template — the same
convention qits-projects-service already uses. Quarkus reads `.env` from the process's **working
directory** at config ordinal 295 — above the packaged `application.properties` (250), below real
environment variables (300) and `-D` (400) — so it overrides the shipped config without a rebuild
and without touching a tracked file, and it works for the native binary exactly as for the
fast-jar. Run from the repo root and it is found. Keys are environment-variable names
(`QITS_PROJECTS_URL`, `QUARKUS_HTTP_PORT`), which is the one cost over a properties file and also
the payoff: the same spellings work unchanged as real env vars in a container. qits-projects-service
carries a matching example putting it on :8090, so the pair starts side by side with no arguments
at all.

The url carries no path because qits-projects serves its own `/projects/api` segment, so the same
base address works whether the call goes direct or through the gateway.

`qits.githost.url` matters more than it used to: it is where every ref read and every ref write
goes. This service holds a **mirror** of each repository under `qits.workspaces.data-dir` (its own
tree, rebuildable — delete it and the next request re-clones), does its merges in a worktree on that
mirror, and reaches the served repository only by pushing. An unreachable git host is no longer a
one-endpoint problem.

**The release door left this service on 2026-09-03**, and with it everything that was only a
release's: `Mode.RELEASE`, the CalVer `VersionStamp`, the pom/package.json bumpers, the annotated
tag, the `-o qits.release` push option (a branch create keeps its own `-o qits.no-ci`), the
`SCMRelease` publication, and the
promotion onto an `environment/*` entry branch (`qits.workspaces.release.entry-branch`, now an unset
key). A release is a **release request in qits-projects**: it folds `main`, the named branches and
every released-but-unmerged tag onto a `release/<id>` branch through qits-githost's git primitives,
the QA pipeline builds that fold, and a green gate stamps the manifests and creates the version tag.
`main` is finalized after the deployment. Nothing here writes a default branch — `/branches/merge`
and `/workspaces/{id}/integrate` refuse it with `RELEASE_REQUIRED`.

**One door a release calls, and it is a workspace-lifecycle door rather than a release one.**
`POST /workspaces/api/branches/resolution?repositoryId=<id>` (`{qits:admin, qits:system}`), body
`{branch, target?, commit?, result?}` → `{resolved, workspaceId?}`. A released branch is deleted on
the git host by a primitive that fires no event, so the workspace standing on it stayed ACTIVE
forever — holding a container, a volume and a commissioned credential for a branch nobody can fetch.
This resolves that one workspace as INTEGRATED, tearing the container, the volume and the credential
down without touching the ref (it is already gone). A branch with no workspace answers
`resolved:false` — the ordinary case, not an error — and the repository's main workspace is refused
on both belts. AGENTS.md, "The release door left, and what stayed", has the reasoning.

One behaviour worth knowing before you debug it: **a missing repository and an unreachable
qits-projects are different answers.** Only a 404 becomes "no such repository" (and then a 404 from
here). A connection failure or a 5xx throws, so an outage surfaces as a 500 naming the address
rather than as a plausible-looking empty registry.

`service/src/main/resources/application.properties` carries what this repo can decide — the segment
prefix (below), the 64M body limit, the OpenAPI/swagger-ui settings. Read it before adding anything;
it explains why each line is load-bearing.

Beyond that, a deployment must allow-list `/workspaces/daemon/` for unauthenticated access — that is
the daemon's dial-home control socket, and it authenticates by workspace id, not by session. In the
monorepo this lived in `auth/core`'s `PublicPaths`; under the gateway it is `PublicPaths` there.
The shared volume of bare origins is **not mounted any more** and there is no config key naming it.
Nothing here wrote to it once every ref move became a push, and its last reader — the fallback for
workspace metadata sidecars written before they moved to this service's own tree — went once no
ACTIVE workspace still had one there. `qits.workspaces.data-dir` is the only tree this service opens.

**Two databases, and the deployment supplies both.** `.config/qits/deployments.yml` declares

    resources: postgresql:db, postgresql:eventstream:qits_workspaces_eventstream

and qits-deployments creates a role and a database for each before the successor container starts,
injecting `QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD` and the matching
`QITS_RESOURCE_EVENTSTREAM_*` triple. `db` is this context's store, read by the `domain` jar's
shipped defaults; `eventstream` is the outbox's, read by the `qits-eventstream` jar's. Neither has a
default behind it: an unset variable is an unresolvable expression and the process dies at Flyway
naming what is missing, rather than opening a store nobody meant.

The `eventstream` database is named explicitly because the derived default would collide with every
other consumer of that library on the same postgres; `db` takes the derived `qits_workspaces`.

**The event bus needs nothing else.** This service publishes no event any more (`SCMRelease` went
with the release door); what remains of the `qits-eventstream` jar is the causation stamp on every
push and the outbox database it boots with. Every remaining key ships as a default — `qits.events.url`
is the qits-net alias. Set it when the bus lives somewhere else:

    QITS_EVENTS_URL=http://qits-events:8080

### The credential a workspace container holds

A workspace container is a **dynamic context**, so it gets an idp client of its own rather than a
share of a durable one: commissioned from qits-idp when the container is provisioned, injected as
container environment, and given back when the container is torn down. That pair is what lets a
workspace pull and push through the edge as *itself* once anonymous reads are gated — the model is
the superproject's `authenticated-reads-plan.md`.

    QITS_COMMISSIONED_CLIENT_ID       the commissioned idp client
    QITS_COMMISSIONED_CLIENT_SECRET   its secret
    QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL  its token endpoint
    QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE   the qits-workspaces audience

The client pair and its control-socket token coordinates are injected together or not at all; a
partial set cannot authenticate and is never a valid container specification. They sit beside
`QITS_WORKSPACE_DAEMON_API_TOKEN`, which points the **other** way — that one is the host proving
itself to the in-container API, this set is the container proving itself to the platform. The daemon
uses the token URL and audience with its client pair to authenticate its dial-home control socket;
Git still asks for its own qits-githost-audience bearer.

**It is scoped to the repository's project.** The commission states
`{"claims":{"project":"<projectId>"}}`, resolved from the repository the workspace branches, and
qits-idp turns that into a `project` claim on every token the pair mints. That is what lets a
resource service judge this container on its project rather than on its platform role alone —
qits-ci's manual trigger reads exactly this claim to decide which repositories a caller may have
evaluated, so a workspace agent reaches its own project's pipelines and nobody else's. The project
the credential is scoped to and the one the container is told about
(`QITS_WORKSPACE_DAEMON_PROJECT_ID`) are the same fact from the same registry.

A project the registry cannot name **costs the scope, not the launch**: the commission states no
claim and the credential is issued as every workspace credential was before scoping existed. A
blinking registry must not be able to stop a workspace from starting, and it is never sent as `"*"` —
qits-idp refuses the wildcard on a commission by design.

**It mirrors the container's lifetime, not the row's.** A provision commissions, a recreate
commissions afresh and hands the old one back, `deleteContainer` hands it back while the workspace
stays ACTIVE (the next start commissions again), and every resolution — integrate, discard, the
branch-gone abandon — hands it back for good. **A stop-then-start keeps it**, because the
orchestrator has no start verb: a stopped container is started by presenting its spec again, and a
spec whose environment moved is a *replaced* container. That is also why the pair lives on the
workspace row rather than being handed in at provision time.

**It is off unless this service holds its own idp credential.** There is no key of ours:
`quarkus.oidc-client.client-enabled` decides it, the same switch that decides whether a machine token
is fetched for qits-containers. Off, nothing is commissioned and no container carries the two
variables — exactly what every workspace did before. Where the commission API answers is derived from
`quarkus.oidc-client.auth-server-url`, so the two can never name different idps.

    QUARKUS_OIDC_CLIENT_CLIENT_ENABLED=true
    QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET=<the secret qits-idp holds for this environment>

**A commissioning failure fails the launch**, after holding through
`qits.workspace.commission.patience` (30s, for an idp mid-redeploy). A workspace is never launched
half-credentialed. **A decommissioning failure never blocks a teardown** — it is logged, and
`wiring/CommissionReconciler` asks qits-idp hourly (and at boot) what it is holding and gives back
whatever no live workspace container claims. That reconcile, not a TTL, is what answers a crash.

## Where it answers

This service has a **host of its own** — `workspaces.<env>.<domain>` — and serves two things on it.
The **client is at `/`**, and every **machine** route stays under `/workspaces`, its segment. The
edge routes `/workspaces/*` **verbatim by prefix** on that host and on every other one, with no
rewriting, so the service serves the prefixed path itself and there is no unprefixed form, on the
edge or on `qits-net`.

| Prefix | What | Set by |
|---|---|---|
| `/workspaces/api/…` | the JSON API — `workspaces`, `branches`, `history`, `events`, `service-events`, `technical-processes`, `capture`, `editor`, `gc`, `pins` | `qits.rest.path`, which `quarkus.rest.path` is derived from |
| `/workspaces/q/…` | `openapi`, `swagger-ui` — what the framework serves, not application code | `quarkus.http.non-application-root-path` |
| `/workspaces/daemon/{id}` | the daemon's dial-home control socket | `DaemonControlSocket`, literal |
| `/workspaces/service/{id}/{serviceId}/*` | the dev-server reverse proxy | `ServiceProxyPath.PREFIX`, literal |
| `/workspaces/container/{id}/*` | the workspace-daemon reverse proxy | `ContainerProxyPath.PREFIX`, literal |
| `/workspaces/daemon/stream/{nonce}` | where a daemon's tunnel dial-back lands | `WorkspaceTunnels.STREAM_PATH_PREFIX`, literal |
| `/` | the SPA, and every client-side route under it — its own paths and the scoped `/<project>/<category>/<repo>/…` | `quarkus.quinoa.ui-root-path` + `enable-spa-routing` |

**One surface is in no row of that table, because it is a HOST and not a path.** Everything arriving
with `X-Forwarded-Host: editor.<project>.<env>.<domain>` is the web editor's and is forwarded whole
to that project's workspace container (`EditorProxyRoute`), over the daemon's reverse tunnel and by
no other route — openvscode-server is bound to the container's loopback, so it has no address on
`qits-net` to dial: openvscode-server serves from `/`, so an
editor claims every path there is and no prefix could name it. It is kept off the SPA fallback by
**route order** rather than by `ignored-path-prefixes` — 1000, ahead of the built client's static
files (1060) and of the fallback (40000) — and once it recognises an editor origin it never falls
through: its 404 and its splash are answers. It sits deliberately *behind* the rows above, which take
Vert.x's own sequence from 0, so the machine surface keeps its paths on every host.

The `/` row is a **fallback over the whole port**, and the six above it are what it must not
swallow. Quinoa derives its skip list from `quarkus.rest.path` and
`quarkus.http.non-application-root-path` only, so the four literal routes would be outside it. The
key is set instead, to one **absolute** entry — `quarkus.quinoa.ignored-path-prefixes=/workspaces` —
which prefix-matches all six rows at once. The values used to be relative to the UI root (`/api`
rather than `/workspaces/api`); stripping a UI root of `/` changes nothing, so they are now what the
request spells. Without the key a mistyped `/workspaces/daemon…` answers `200 text/html` with
`index.html`, which a machine client parses as data; with it, it answers 404. Add a **root-level**
route, add its prefix.

These are second-level segments beside `api` because none is a JSON API, and all are
literals for the same reason: **raw Vert.x routes and `@WebSocket` paths do not follow
`quarkus.rest.path`.** So is `CaptureCorsRoute`'s preflight, which derives its path from the prefix
rather than repeating it — a preflight on a different path from the POST it clears is worth nothing.
`RootPath` is where that arithmetic lives.

That derivation reads `qits.rest.path` and not `quarkus.rest.path`, and the difference is not
cosmetic: `quarkus.rest.path` is fixed at build time and is therefore absent from a native image's
runtime config, so the lookup took its default in the binary and put the preflight on an
unreachable `/capture`. `application.properties` spells the value once, as `qits.rest.path`, and
`quarkus.rest.path` is `${qits.rest.path}` — one value, readable in both runtimes.

`/workspaces/daemon/{id}` is a **cross-repo contract**: `WorkspaceContainerFactory` injects
`ws://<qits-host>:<port>/workspaces/daemon/<id>` as `QITS_WORKSPACE_DAEMON_URL` into every container
it creates, and qits-workspace-daemon dials exactly that. `LegacyDaemonControlSocket` still answers
the pre-segment label path for containers provisioned before the move; its own javadoc says why it
keeps no `/workspaces` segment.

`/workspaces/container/{id}/*` is **the only way anything reaches a workspace-daemon.** Its HTTP API
— the file browser, commands, coding agents, services, bootstrap and the two interactive websockets
— had no address at all before it: no gateway route, and no `QITS_WORKSPACE_DAEMON_API_TOKEN`
injected, so the daemon's server did not even bind. This service injects that token and proxies to
`13338` on the container's own DNS name, resolved from the workspace row and from nothing in the
request. The daemon is deliberately **not** a gateway route — one process per container, living for
one container lifetime, has no stable address to configure — and `container` rather than `daemon`
because the control socket already owns that segment and its literal is baked into running
containers. `ContainerProxyPath` carries the argument; `DaemonProxyTargets` does the lookup.

**A daemon at `DaemonProtocol.TUNNEL_CAPABILITY_VERSION` or above is not reached at `13338` at all.**
It binds `127.0.0.1` and has no address on `qits-net`, so the proxy's origin is instead a loopback
listener this service opens per workspace (`WorkspaceTunnels`); each accepted connection mints a
single-use nonce, asks that workspace's daemon over the control socket to come and get it, and the
daemon dials back to `/workspaces/daemon/stream/{nonce}` and pipes it to its own API. The tunnel
carries bytes, so an HTTP request and a WebSocket upgrade traverse it identically.

The two ways are keyed by that one version and are strictly complementary — a daemon that serves
streams has stopped listening, one that still listens knows nothing about `OpenStream` — so there is
no ambiguous middle, and a daemon that has not said hello yet counts as not capable. That is the
safe direction: an image old enough to predate the tunnel is old enough to still be listening.

Two things it does not do, both on purpose. It does not authorize the caller — qits is single-user
and a workspace has no owner; the check is that the id names an ACTIVE row, and an unknown id, a
non-numeric one and a soft-deleted row all answer the same 404 before anything connects. And it does
not gate on control-socket liveness: the daemon's HTTP server and its socket are independent
listeners, so refusing while a socket is in reconnect backoff would take file browsing and every
open terminal down for the length of a blip.

**`GET /workspaces/api/pins` says which images a launch by this process would pull** —
`{generatedAt, pins:[{image, version, launches}]}`, one row per configured pair (`workspace`,
`editor`), the image registry-relative and a blank version omitted. It is a pin source for
qits-artifacts' registry GC, read by qits-platform-orchestrator with a bearer and open to
`qits:admin` beside `qits:system`, like `/workspaces/api/gc/branches`. It answers the **effective**
version — what this running process resolved at boot — where qits-configuration answers the
configured one, and the two differ until this service is deployed again; a launch pulls cold, so the
lagging value is the one the GC must keep. It reads the same config `WorkspaceContainerFactory`
launches from and dials nobody.

The workspace routes take **no repository segment**: this context does not own repositories, so
collections filter by `?repositoryId=` and a workspace is `{id}` alone. `AGENTS.md` has the rest.

## What is deliberately *not* here

**Already in the daemon**, which is why they are absent here rather than pending:

- file access (`/files`, `/files/content`) — the daemon's `workspace-daemon-files` module, `java.nio`
  where the host shelled `docker exec find/cat/realpath`
- framework detection and the component map (`/detection`, `/component-map`) —
  `workspace-daemon-detection`
- bootstrap chain *execution* — the daemon's `BootstrapRunner`. The host's awaiter and the run record
  are here (`WorkspaceBootstrapRunner`, `workspace_bootstrap_run`); the **surface over it is not, any
  more** (below)
- dev-server *execution* — the daemon's `ServiceSupervisor`. The host's projection of it, the event
  feed and the proxy are here (`ServiceSupervisor`, `service_event`, `ServiceProxyRoute`); the
  start/stop surface is not, any more (below)
- `.config/qits/repository.yml` parsing — the daemon's `ConfigParser`. This context holds the shape
  (`QitsConfig`) and reads it back over the socket (`WorkspaceConfigReader`), never the file
- periodic checkpoint push — deleted rather than relocated; the daemon's `OriginSync` pushes per
  commit within ~500ms, so the sweep was redundant

Staying with their own contexts: repositories, projects, commits and conflict resolution, commands,
agents, telemetry and feature flows.

**No longer addressable from the host at all**: `/services`, `/services/{id}/start|stop`,
`/bootstrap-commands`, `/bootstrap-commands/run` and `/bootstrap-commands/{stepId}/run`. Both ran
inside the container and the host only forwarded, which is the same defect twice and the last two
capabilities still shaped that way — everything else took its addressing into the daemon's own HTTP
API when its execution moved. `WorkspaceServiceController` and `WorkspaceBootstrapController` are
deleted; the daemon's `WorkspaceApi` is where the endpoints belong, and it has them — reached
through `ContainerProxyRoute`, which is the only address a daemon has and deliberately not a gateway
route (one process per container has none to configure).

**What was removed is the externally addressable surface, not the capability.** The
provision → bootstrap → services sequence is host-orchestrated and untouched: `WorkspaceBootstrapRunner`
still runs the chain on `WorkspaceContainerStarted` and fires `ReadyForServices`, `ServiceLifecycleCoupler`
still auto-starts services on it, and `ServiceProxyRoute` still reads `ServiceSupervisor` state to
resolve a port. Both host projections stay too — `service_event` with its SSE feed, and
`workspace_bootstrap_run`, which spent a release with **no reader** and now has one:
`GET /workspaces/api/workspaces/{id}/bootstrap-runs`. That is a read of a host table, not the
forwarding controller returning — the run verbs stay on the daemon, and a client joins the two on
`bootstrapCommandId`.

Still host-side but *should* follow the file/detection work into the daemon: `containerGit` and its
callers (`fast-forward`, `update-from-parent`, `pushBranch`, `isFullyPushed`) still shell git into
the container. Migrating them needs new wire messages on both sides, so it was kept out of the
extraction rather than smuggled into it.

Not asserted anywhere any more, dropped when their setup could not come along: the
`CommandRegistry` PTY attach path (`ServiceAttachTerminalTest`), the delivery half of the agent sink
(now `WorkspaceChatInbox`'s contract), the repository-delete cascade onto `workspace_bootstrap_run`
(it starts in another database), and the depth-2 submodule closure
(`WorkspaceSubmoduleProvisionTest` — its fixtures belong to qits-projects-service).

Not covered anywhere yet: **startup reconciliation** of workspaces against the live container set
(containerless-but-live-branch → STOPPED, dangling-volume reaping). That logic lives in the
repositories context's `RepositoryDiscoveryService`, which walks the repositories data dir; this
context has no reconciler of its own.

Released through the new release-request flow on 2026-09-04, verifying the deploy path end to end.
