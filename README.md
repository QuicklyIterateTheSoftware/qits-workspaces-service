# qits-workspaces-service

The **host side** of qits workspaces: workspaces and the agents that run in them, their scheduling
and lifecycle, container orchestration, host-side git through a mirror of each repository, the
workspace-daemon registry, the technical-process framework, prompt composition, feature capture, and
the routes over all of it — the machine surface at `/workspaces`, its segment. It serves no web
client; the workspace pages are qits-landing-app's.

Since epic qits-1152 a **workspace** is a slot: one container in a WORKSPACE slot of an execution
target (epic qits-1481; an admin one on the platform host), scoped to a project's wrapper, holding one long-lived clone. An **agent** is one
work item's coding agent and its worktree inside a workspace; a workspace hosts many agents and runs
one at a time. The agent's branches are its own: the daemon creates the wrapper branch locally, the
agent names its submodule branches, and each push is reported back. This service does **not** release
anything — a release is a release request in qits-projects, which folds the sources through
qits-githost and lands a tag. AGENTS.md, "Agents and the workspaces they run in", has the model. Everything that runs *inside* the container belongs to
[qits-workspace-daemon](https://github.com/QuicklyIterateTheSoftware/qits-workspace-daemon).

    mvn verify        # resolves qits-eventstream 1.0.0 from local qits-artifacts

## Layout

| Module | What |
|---|---|
| `workspaces-events/` | `eu.wohlben.qits.workspaces.events` — the lifecycle vocabulary (`qits-workspaces-events`): plain records over qits-eventstream, what this service publishes about agents and workspaces. |
| `gitmirror/` | `eu.wohlben.qits.workspaces.gitmirror` — the git substrate: a local mirror per repository, the worktrees a merge runs in, and the pushes that are the only way a ref moves. Framework-free; its tests run offline against throwaway bares. |
| `domain/` | `eu.wohlben.qits.workspaces.*` — entity, persistence, dto, mapper, control, and the framework-free SPIs the daemon implements. No web, no JAX-RS. |
| `service/` | `eu.wohlben.qits.workspaces.{api,daemonhost}` — JAX-RS routes, the SSE channels, and the daemon control socket + registry. |
| — | The daemon wire contract is no longer a module here. It is the released `eu.wohlben.qits:qits-workspace-daemon-protocol`, and **its version is the `qits/workspace` image this service starts** — see AGENTS.md, "The protocol is a dependency, and its version is the workspace image". |

**No web client.** The service serves no SPA: the workspace pages are qits-landing-app's (epic
qits-1230), and the qits-workspaces-frontend submodule that used to sit at `service/src/main/webui/`
went with Quinoa in qits-1245. A plain clone is a complete checkout, and a build needs no node.

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
| `WorkspaceProcessTracker` | *implemented here* | `TechnicalProcessRegistry` is the default; the port stays so an application can substitute its own |
| `AgentDaemon` | *implemented here* | `DaemonAgentClient` (the daemon's `agent-worktrees` API through the tunnel); absent means agents are placed but never started |
| `WorkFiler` | *implemented here* | `HttpWorkFiler` (`POST /projects/api/work`); absent means a capture is a 500 |
| `CredentialCommissioner` | no | no container is given a platform credential — today's behaviour |
| `ContainerRuntime` | *implemented here* | `WorkspaceContainers`, over qits-containers — **admin workspaces only**; every regular workspace runs under a qits-system lease (`TargetPlacement`) and never reaches it (qits-780) |
| `LifecycleAnnouncer` | *implemented here* | `EventstreamLifecycleAnnouncer`, onto the bus (qits-1236); absent means the lifecycle events are not published and nothing else changes |

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
tag, the `-o qits.release` push option (a branch create is an ordinary push now too — CI is gated
only by release requests), the `SCMRelease` publication, and the
promotion onto an `environment/*` entry branch (`qits.workspaces.release.entry-branch`, now an unset
key). A release is a **release request in qits-projects**: it folds `main`, the named branches and
every released-but-unmerged tag onto a `release/<id>` branch through qits-githost's git primitives,
the QA pipeline builds that fold, and a green gate stamps the manifests and creates the version tag.
`main` is finalized after the deployment. Nothing here writes a default branch — `/branches/merge`
refuses it with `RELEASE_REQUIRED`.

**One door a release calls.** `POST /workspaces/api/branches/resolution?repositoryId=<id>`
(`{qits:admin, qits:system}`), body `{branch, target?, commit?, result?}` → `{resolved, agentIds}`. A
released branch is deleted on the git host by a primitive that fires no event, so the release says
so here: every ACTIVE agent whose wrapper branch it is records the release (a branch an agent merely
pushed marks nobody). It becomes OBSOLETE once its work item is closed too (VERIFIED, DONE or
DROPPED), in whichever order the two happen — at once when the item is already closed, else when it
closes (qits-1605); a closed item's agent whose wrapper branch no release consumed is OBSOLETE too
once that branch is gone from the git host or merged into the default branch — and the cleanup
sweep removes it once it has been idle (leftover work is handed
back to the agent, never deleted). A branch nothing owns answers `resolved:false` — the ordinary case.

**The machine doors qits-projects presses** (`{qits:admin, qits:system}`), every one keyed by the
work item:

- `POST /workspaces/api/agent-dispatches` `{repositoryId, wrapperBranch, workId, entityId,
  entityTitle, entityStatus, entityBlocked, ticketId?, epicId?, instruction}` → `{agent,
  fresh, agentLaunch, agentIdentity}`: there should be an agent working on this item. Idempotent; a
  queued agent waits while a lease of this service already waits for a slot. `admin: true` is refused: only a person's door makes
  an admin agent.
- `POST …/agent-dispatches/delivery` `{workId, text, compactFirst}` → `{agentId, delivered, launched,
  resumed, detail}`: say this to the item's agent. Never creates one; `agentId: null` is the answer
  for an item nobody works on.
- `POST …/agent-dispatches/entity` and `…/blocked` → `{agentId, applied}`; `GET
  …/agent-dispatches/references?workId=…` → `entries[].agent`.

A person's doors are `GET`/`POST /workspaces/api/agents` (an agent for an existing work item, with
`admin` for the docker socket), `GET /workspaces/api/agents/{id}` and `POST …/{id}/discard`.

**Declared waits** (qits-1153; `qits wait` is the caller). An agent says what event it waits on and
gets a turn when it happens:

- `POST /workspaces/api/agents/{agentId}/waits` `{label?, selection: {event, when: [[{path, op:
  exact|prefix|exists, value}]]}, timeout?: ISO-8601}` → 201 `{id, agentId, label, selection,
  state: OPEN|MATCHED|TIMED_OUT|CANCELLED, createdAt, expiresAt, matchedEventId, closedAt}`. The
  selection is qits-eventstream's `EventSelection`; an invalid one is a 400 with its reason. At most
  8 OPEN waits per agent (`409 TOO_MANY_WAITS`); timeout at most 30 days.
- `GET …/waits?state=` → `{waits: [...]}`; `DELETE …/waits/{waitId}` → 200 with the wait
  (CANCELLED, or as it was if already closed).
- `qits:agent` and `qits:admin-agent` act only on the agent their token belongs to; `qits:admin` and
  `qits:system` on any.
- A durable bus listener (`qits-workspaces.agent-waits`, subscribed to `*`) matches arriving events.
  A match or a timeout closes the wait and gives the agent a turn naming the label, the event, its
  time and the matched values (never the raw payload); a yielded agent resumes for it. `GET /agents/{id}` shows `waitingOn`,
  and the waiting relay to qits-projects says `cause: "Wait"` with `waitingOn` instead of a plain
  wait for input.

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

**The event bus needs nothing else.** This service publishes the agent and workspace lifecycle
(`AgentPlaced` … `AgentRemoved`, `WorkspaceStarted`/`Failed`/`Removed`; qits-1236, see AGENTS.md,
"The lifecycle events"), listens once for declared waits, and stamps the causation id on every push;
an event the inline PUT does not deliver waits in the outbox database above. Every remaining key
ships as a default — `qits.events.url` is the qits-net alias. Set it when the bus lives somewhere
else:

    QITS_EVENTS_URL=http://qits-events:8080

### Where a workspace runs

`Workspace.placement` (DIRECT or TARGET, `V12`, `V30`) is decided once, at create, and never changed
(qits-837, qits-774). The split is by posture and nothing else: **a regular workspace runs in a
WORKSPACE slot of an execution target (TARGET), and only admin workspaces use the direct path** — a
container on the platform host through qits-containers (DIRECT). An admin workspace holds the host's
docker socket, which no slot type gives. `WorkspacePlacements.forNewRow(admin)` writes it.

Three things hold the rule, from the schema in:

- **`ck_workspace_runner_posture`** (`V12`, rewritten in `V20`): an admin row is never TARGET.
- **`ck_workspace_direct_only_admin`** (`V15`, rewritten in `V20` and `V30`): an ACTIVE row is
  TARGET or admin — so no ACTIVE regular row can be DIRECT. Resolved rows keep their placement as
  history.
- **The router's refusal**: every DIRECT branch of every verb — start, recreate, stop,
  delete-container, discard, the listing, the clean/pushed probes and the daemon
  proxy's lookup — calls `WorkspacePlacements.requireDirectAllowed` before
  it reaches `ContainerRuntime`, and a regular row there is an ERROR `direct placement refused for
  regular workspace <id>` and an `IllegalStateException`, never a container on the platform host.

### TARGET workspaces: a lease from qits-system (epic qits-1481)

A TARGET row's container runs under a **WORKSPACE lease** qits-system grants on an execution target
(`targethost/TargetLeasePlacement`, over `qits.system.url`, with this service's own `qits` oidc
client). The workspace runner this service once registered, and its socket, doors, install line and
idp kinds, are gone (`V30` drops `workspace_runner`).

- **Start** mints the lease's `workspace` token, composes `LeaseSpec{image, env, user 1000,
  limits}` (`RunnerWorkspaceSpecs`, never `HOME`, `CLAUDE_CONFIG_DIR` or `BUILDKIT_HOST`), and asks
  `POST /system/api/leases` with `slotType WORKSPACE`, `ownerRef <rowId>`, `workKey ws-<rowId>`.
  The row records `lease_id` and waits QUEUED, the lease's `detail` (why it waits) as its
  `runtimeError`. qits-system not answering is 409 `TARGET_UNAVAILABLE` and the row as it was; a
  refusal of the request itself fails the row with `TARGET_REFUSED: …`.
- **The lease's state is the row's** (`TargetLeaseStates`), from qits-system's `TargetLeaseChanged`
  and a reconcile from `GET /system/api/leases` every
  `qits.workspaces.target-leases.reconcile-interval` (PT30S): QUEUED → QUEUED, GRANTED →
  PROVISIONING (`target_id` set), RUNNING → RUNNING once the daemon reported its self-clone, EXITED →
  STOPPED, FAILED → FAILED, ENDED → STOPPED and the lease cleared. The token is deleted when the
  lease reports ENDED, EXITED or FAILED.
- **Stop** ends the lease (`DELETE /system/api/leases/{id}`); the work volume stays on its target.
  **Delete-container** ends it, waits for ENDED and purges the work volume
  (`DELETE /system/api/work-volumes/ws-<rowId>`). A resolution ends it, and purges the volume once
  it is ENDED.
- **A row a retired runner held** (`legacy_placement`, copied by `V30`) is adopted by its first
  lease: `adopt{container, volume}` = `qits-workspaces-runner-ws-<rowId>-<oldRunnerId8>` (kept on the
  row as `adopted_volume`, so every later lease carries it) and `pinTargetId` the target on the same
  node. Until that target is installed the row waits QUEUED, asking for nothing ("waiting for target
  <runner> to be installed (it holds this workspace's volume)"), and each lease reconcile retries
  it. `targethost/LegacyRunnerClients` gives the retired runner kinds' idp clients and tokens back
  at every boot.

The agent scheduler asks for a new regular workspace only while no TARGET row of this service
waits QUEUED; otherwise an agent waits QUEUED, with no workspace. qits-system decides which target
has room. The owner-wide qits-containers listing is asked for only when the repository has a DIRECT
row, so a repository of regular workspaces costs qits-containers nothing.

**Kept for the direct path, deliberately.** All of it serves the admin workspaces, and
none of it is a leftover of regular DIRECT rows:

- `containershost/WorkspaceContainers` and `containers/ContainersClientProducer` — the
  `ContainerRuntime` over qits-containers that admin containers are started, stopped and
  removed through.
- `qits.containers.url` / `QITS_CONTAINERS_URL` — where that orchestrator answers.
- `qits.workspace.containers.owner` — the owner every admin container and volume is
  claimed under, so two environments never name each other's.
- `qits.workspace.containers.launch-patience` — admin launches still use the retry loop
  in `WorkspaceContainers.run`.
- The internal-plane keys — `qits.workspace.network`, `qits.workspace.git-host`,
  `qits.workspace.qits-port`, `qits.workspace.container-git-url` — what a container on `qits-net`
  dials home and clones through.
- The volume and resource keys — `qits.workspace.workspace-volume-prefix`, `claude-volume`,
  `maven-volume`, `pnpm-volume`, `memory-limit`, `memory-swap-limit` — the platform host's volumes
  and limits for admin containers (the limits are a TARGET lease's too).
- `commissionFor`, `CommissionReconciler` and the `V3` columns — the commissioned client pair is the
  admin container's credential on a deployment with **no** edge plane. With a public
  `QITS_DOMAIN` an admin container holds a workspace token instead, as every TARGET row's
  lease does (qits-1084) — see "The credential a workspace container holds".

### The credential a workspace container holds

A workspace container is a **dynamic context**, so it gets an idp client of its own rather than a
share of a durable one: commissioned from qits-idp when the container is provisioned, injected as
container environment, and given back when the container is torn down. That pair is what lets a
workspace pull and push through the edge as *itself* once anonymous reads are gated — the model is
the superproject's `authenticated-reads-plan.md`.

    QITS_COMMISSIONED_CLIENT_ID       the commissioned idp client
    QITS_COMMISSIONED_CLIENT_SECRET   its secret
    QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL  its token endpoint
    QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE   the platform audience, qits-platform (C4)

**On a deployment with a public `QITS_DOMAIN` an admin container holds a workspace token
instead of that pair** (qits-1084) — the same non-expiring `qits_tok_` every TARGET container holds,
minted at `POST /idp/api/tokens` and stored on the row. An admin row's is of kind `workspace-admin`
(so `qits:agent` and `qits:admin-agent`), the editor's of kind `workspace` and unscoped. The
container carries

    QITS_TOKEN                        the token
    QITS_TOKEN_SUBJECT                its subject, tok-…: what the daemon socket binds the agent to
    GIT_CONFIG_GLOBAL                 /etc/qits-gitconfig, the image's git credential helper
    QITS_GIT_AUTH_HOST                githost.qits.<domain>, the one host that helper answers for

and none of the pair block above. Because the edge is the only thing that accepts a `qits_tok_`
(it exchanges it for a JWT), such a container's daemon socket, MCP server and git base are the
public `<app>.qits.<domain>` names rather than the internal ones; it is otherwise the same DIRECT
container — on qits-net, the admin one with the docker socket, the editor with its port and project
list. With **no** public domain the pair is commissioned as before and a WARN says so; that is the
only case the pair is still for. A container keeps whichever credential it was launched with until it
is recreated or deleted, and teardown and the hourly reconcile treat the token exactly as they treat
the pair.

The client pair and its control-socket token coordinates are injected together or not at all; a
partial set cannot authenticate and is never a valid container specification. They sit beside
`QITS_WORKSPACE_DAEMON_API_TOKEN`, which points the **other** way — that one is the host proving
itself to the in-container API, this set is the container proving itself to the platform. The daemon
uses the token URL and audience with its client pair to authenticate its dial-home control socket;
Git asks for the same one platform audience now (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4) rather than a
qits-githost-specific one. The socket takes `qits:system` or `qits:agent`; a token with `qits:agent`
alone opens only the socket of the workspace whose commissioned client is its `sub`, and any other
path is a 403.

**It is scoped to the repository's project.** The commission states
`{"claims":{"project":"<projectId>"}}`, resolved from the repository the workspace branches, and
qits-idp turns that into a `project` claim on every token the pair mints. That is what lets a
resource service judge this container on its project rather than on its platform role alone —
qits-ci's manual trigger reads exactly this claim to decide which repositories a caller may have
evaluated, so a workspace agent reaches its own project's pipelines and nobody else's. The project
the credential is scoped to and the one the container is told about
(`QITS_WORKSPACE_DAEMON_PROJECT_ID`) are the same fact from the same registry.

**An admin workspace's credential is of its own kind** (qits-628 follow-up). An ADMIN row is
commissioned with `contextKind: "workspace-admin"`, which qits-idp issues `qits:agent` **and**
`qits:admin-agent` — the role every door here (and on the platform) that admits `qits:admin` also
admits by name, because that container already holds the host's docker socket. Every other row, the
editor included, stays `contextKind: "workspace"` and `qits:agent` alone. The kind is the issuer's
record and is not in the container's environment, so the spec — and with it `Recreate.ifChanged` — is
unchanged by it. **An admin container that already exists keeps the `workspace` credential it was
launched with** until it is next recreated or deleted (a stop and start keeps the pair, by design);
only then is a `workspace-admin` one commissioned. `CommissionReconciler` reaps and spares both kinds
by the one rule. **qits-idp must be released first**: an idp that does not know the kind gives it no
role at all, not even `qits:agent`.

A project the registry cannot name **costs the scope, not the launch**: the commission states no
claim and the credential is issued as every workspace credential was before scoping existed. A
blinking registry must not be able to stop a workspace from starting, and it is never sent as `"*"` —
qits-idp refuses the wildcard on a commission by design.

**It states that the container may push nothing**: `"gitRefs": []`, never an absent list, which
would be unscoped. Since qits-1152 the push scope is the agent's — its token carries the refs of its
own branch (`agent_worktree.git_refs`), and the daemon pushes with it — so a workspace slot has no
refs of its own. The per-workspace list and its narrowing (contracts C4 and C5 in the superproject's
`principal-bound-git-refs-plan.md`) are gone, and `V27` dropped their columns (qits-1385).

**It mirrors the container's lifetime, not the row's.** A provision commissions, a recreate
commissions afresh and hands the old one back, `deleteContainer` hands it back while the workspace
stays ACTIVE (the next start commissions again), and every resolution — discard, the
branch-gone abandon — hands it back for good. **A stop-then-start keeps it**, because the
orchestrator has no start verb: a stopped container is started by presenting its spec again, and a
spec whose environment moved is a *replaced* container. That is also why the pair lives on the
workspace row rather than being handed in at provision time.

**It is off unless this service holds its own idp credential.** There is no key of ours:
`quarkus.oidc-client.qits.client-enabled` decides it, the same switch that decides whether a machine
token is fetched for qits-containers. Off, nothing is commissioned and no container carries the two
variables — exactly what every workspace did before. Where the commission API answers is derived from
`quarkus.oidc-client.qits.auth-server-url`, so the two can never name different idps.

**The credential is the deployer's `idp:client` resource, and configuring nothing is the normal
state.** `.config/qits/deployments.yml` declares it, and the deployer injects `QITS_RESOURCE_IDP_URL`,
`QITS_RESOURCE_IDP_CLIENT_ID` and `QITS_RESOURCE_IDP_CLIENT_SECRET`; every deployment has the
resource, so `client-enabled` ships **true** (off under `%dev`/`%test`) and there is no switch to
flip by hand. Configuring `QUARKUS_OIDC_CLIENT_*` sets nothing — the old unnamed-client fallback is
gone.

**A commissioning failure fails the launch**, after holding through
`qits.workspace.commission.patience` (30s, for an idp mid-redeploy). A workspace is never launched
half-credentialed. **A decommissioning failure never blocks a teardown** — it is logged, and
`wiring/CommissionReconciler` asks qits-idp hourly (and at boot) what it is holding and gives back
whatever no live workspace container claims. That reconcile, not a TTL, is what answers a crash.

## Where it answers

This service has a **host of its own** — `workspaces.<env>.<domain>` — and serves only the
**machine** surface on it, every route under `/workspaces`, its segment. The
edge routes `/workspaces/*` **verbatim by prefix** on that host and on every other one, with no
rewriting, so the service serves the prefixed path itself and there is no unprefixed form, on the
edge or on `qits-net`.

| Prefix | What | Set by |
|---|---|---|
| `/workspaces/api/…` | the JSON API — `workspaces`, `agents`, `branches`, `history`, `events`, `technical-processes`, `capture`, `agent-dispatches`, `work`, `gc`, `pins` | `qits.rest.path`, which `quarkus.rest.path` is derived from |
| `/workspaces/q/…` | `openapi`, `swagger-ui` — what the framework serves, not application code | `quarkus.http.non-application-root-path` |
| `/workspaces/daemon/{id}` | the daemon's dial-home control socket | `DaemonControlSocket`, literal |
| `/workspaces/container/{id}/*` | the workspace-daemon reverse proxy | `ContainerProxyPath.PREFIX`, literal |
| `/workspaces/daemon/stream/{nonce}` | where a daemon's tunnel dial-back lands | `WorkspaceTunnels.STREAM_PATH_PREFIX`, literal |
| anything else — `/`, `/workspaces`, `/workspaces/` included | **404**. The SPA that answered here is gone (qits-1245); the workspace pages are qits-landing-app's, at `/projects/<slug>/workspaces…` on the apex domain | — |

**The web editor's vhost is gone** with the editor (qits-1152; epic qits-1150).

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
it creates, and qits-workspace-daemon dials exactly that. The pre-segment label path
(`LegacyDaemonControlSocket`, `/api/workspace-daemon/{label}`) is gone (qits-780): no daemon had
dialled it in a week.

`/workspaces/container/{id}/*` is **the only way anything reaches a workspace-daemon.** Its HTTP API
— the file browser, commands, coding agents, bootstrap and the two interactive websockets
— had no address at all before it: no gateway route, and no `QITS_WORKSPACE_DAEMON_API_TOKEN`
injected, so the daemon's server did not even bind. This service injects that token and proxies
through the daemon's reverse tunnel, found from the workspace row id and from nothing in the
request. The daemon is deliberately **not** a gateway route — one process per container, living for
one container lifetime, has no stable address to configure — and `container` rather than `daemon`
because the control socket already owns that segment and its literal is baked into running
containers. `ContainerProxyPath` carries the argument; `DaemonProxyTargets` does the lookup.

**The reverse tunnel is the only way to a daemon.** A daemon at
`DaemonProtocol.TUNNEL_CAPABILITY_VERSION` or above binds `127.0.0.1` and has no address on
`qits-net`, so the proxy's origin is a loopback listener this service opens per workspace
(`WorkspaceTunnels`); each accepted connection mints a single-use nonce, asks that workspace's daemon
over the control socket to come and get it, and the daemon dials back to
`/workspaces/daemon/stream/{nonce}` and pipes it to its own API. The tunnel carries bytes, so an HTTP
request and a WebSocket upgrade traverse it identically. The direct `container:13338` fallback for
older daemons, and the `qits.workspace.daemon-api-port` key it read, were deleted in qits-780 (no
live daemon was below the tunnel capability): a daemon with no tunnel answers 503 `workspace daemon
not connected`, or 502 when its DIRECT container is not running. `13338` survives only as the
authority a proxied request presents (`ContainerProxyPath.DAEMON_API_PORT`), never as an address.

Two things it does not do, both on purpose. It does not authorize the caller — qits is single-user
and a workspace has no owner; the check is that the id names an ACTIVE row, and an unknown id, a
non-numeric one and a soft-deleted row all answer the same 404 before anything connects. And it does
not gate on control-socket liveness: the daemon's HTTP server and its socket are independent
listeners, so refusing while a socket is in reconnect backoff would take file browsing and every
open terminal down for the length of a blip.

**`GET /workspaces/api/pins` says which images a launch by this process would pull** —
`{generatedAt, pins:[{image, version, launches}]}`, one row per configured pair (`workspace`; the
retired runner's image row went with it, epic qits-1481), the image registry-relative and a blank version omitted. It is a pin source for
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
- `.config/qits/repository.yml` parsing — the daemon's `ConfigParser`. This context holds the shape
  (`QitsConfig`) and reads it back over the socket (`WorkspaceConfigReader`), never the file
- periodic checkpoint push — deleted rather than relocated; the daemon's `OriginSync` pushes per
  commit within ~500ms, so the sweep was redundant

Staying with their own contexts: repositories, projects, commits and conflict resolution, commands,
agents, telemetry and feature flows.

**Workspace services — dev servers declared under `services:` — are not here, and not anywhere
(qits-947).** The concept was taken out of scope and removed end to end: the host projection
(`ServiceSupervisor`), its event feed (`service_event`, `/workspaces/api/service-events`), the
dev-server proxy (`/workspaces/service/{id}/{serviceId}/*`) and the auto-start coupling are deleted,
and nothing replaces them, nor does any `SERVICE*` env var reach a container. `AGENTS.md` lists
the two tolerances kept for daemons built before the removal.

**The bootstrap chain is gone** (qits-1152, D19), host and daemon: agents set up what they need
themselves.

Still host-side but *should* follow the file/detection work into the daemon: `containerGit` and its
callers (`fast-forward`, `update-from-parent`, `pushBranch`) still shell git into
the container. Migrating them needs new wire messages on both sides, so it was kept out of the
extraction rather than smuggled into it.

Not asserted anywhere any more, dropped when their setup could not come along: the
`CommandRegistry` PTY attach path (`ServiceAttachTerminalTest`), the delivery half of the agent sink, the repository-delete cascade onto `workspace_bootstrap_run`
(it starts in another database), and the depth-2 submodule closure
(`WorkspaceSubmoduleProvisionTest` — its fixtures belong to qits-projects-service).

Not covered anywhere yet: **startup reconciliation** of workspaces against the live container set
(containerless-but-live-branch → STOPPED, dangling-volume reaping). That logic lives in the
repositories context's `RepositoryDiscoveryService`, which walks the repositories data dir; this
context has no reconciler of its own.

Released through the new release-request flow on 2026-09-04, verifying the deploy path end to end.
