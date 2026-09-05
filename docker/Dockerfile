# qits-workspaces as a GraalVM-native container IMAGE — the same shape as qits-artifacts' and
# qits-workspace-daemon's docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal
# runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces
# `service/target/qits-workspaces` with no container involved. What this adds is the image around it
# — and a way for a machine with no GraalVM to get a binary at all, since the builder stage brings
# its own native-image.
#
# Build from the repo root:
#
#     docker build -t qits/workspaces:latest -f docker/Dockerfile .
#
# --- this image does NOT run with an empty environment, and that is not a packaging bug ------------
# `docker run --rm qits/workspaces:latest` dies before the HTTP listener, at Flyway, because both of
# its datasources are the platform's generic resource contract and nothing else:
# ${QITS_RESOURCE_DB_URL} for this context's store and ${QITS_RESOURCE_EVENTSTREAM_URL} for the
# outbox, with no defaults behind them. An unset variable is an unresolvable expression, and the
# process says which one is missing.
#
# That is the refuse-to-boot stance, and it is deliberate: a database this service opens is provided
# by the deployment or the service does not run. qits-deployments creates the role and the database
# for each resource named in .config/qits/deployments.yml and injects the triples into the container
# it starts, so on the platform this is arranged before the process exists.
#
# IT USED TO FAIL HERE FOR A WORSE REASON, kept because the shape recurs. The store was a file H2
# whose default url was `jdbc:h2:file:${user.home}/...`, and UID 1001 has no /etc/passwd entry in
# ubi-minimal while a native image resolves user.home through getpwuid(2) rather than $HOME — so
# `${user.home}` became `?`, setting HOME did not fix it, and the message was about a relative path
# rather than about a missing setting. A default with no feature in it cannot lose one.
#
# One `${user.home}`-rooted path is left, and this image names it:
#
#   this service's    the git mirrors (one bare per repository, plus the worktrees a merge runs in)
#   own tree          and the workspace metadata sidecars. Named here —
#                     QITS_WORKSPACES_DATA_DIR=/data/workspaces — because it is a private cache on
#                     the volume this image already owns, with no contract to agree with anybody
#                     about. It is rebuildable: delete it and the next request re-clones.
#
# There used to be another, naming the shared volume of bare origins qits-artifacts serves, mounted
# here so this service could write refs into them directly. That is what made a branch create, a
# merge or a cleanup fire no post-receive and so produce no CI run. Every one of them is a push to
# the git host over HTTP now, the sidecars this service used to keep on that volume moved to its own
# tree, and the key is gone from the config with them — nothing here opens the shared volume, so
# nothing here mounts it.
#
# So a deployment mounts a volume at /data (which this image owns, see the runtime stage) for that
# tree, and supplies the two database triples plus the one address this service cannot invent:
#
#   docker run -v qits-workspaces-data:/data \
#     -e QITS_RESOURCE_DB_URL=jdbc:postgresql://qits-oci-postgresql:5432/qits_workspaces \
#     -e QITS_RESOURCE_DB_USERNAME=qits_workspaces -e QITS_RESOURCE_DB_PASSWORD=… \
#     -e QITS_RESOURCE_EVENTSTREAM_URL=jdbc:postgresql://qits-oci-postgresql:5432/qits_workspaces_eventstream \
#     -e QITS_RESOURCE_EVENTSTREAM_USERNAME=qits_workspaces_eventstream \
#     -e QITS_RESOURCE_EVENTSTREAM_PASSWORD=… \
#     -e QITS_PROJECTS_URL=http://qits-projects:8080 \
#     qits/workspaces:latest
#
# `qits.projects.url` has no default and `wiring/HttpRepositoryLookup` REFUSES TO START without it: a
# workspace is a branch of a repository and this context holds no foreign key into the repositories
# tables, so with no registry to ask the service would come up 404ing every repository-scoped route —
# a misconfiguration wearing the costume of an empty system. README.md, "Deploying it", has the rest
# of the config surface.
#
# One consequence of the missing passwd entry that does NOT fail loudly, recorded so it is not
# rediscovered: `WorkspaceContainerFactory.hostUid()` stats `${user.home}` to make a container's
# /workspace files match the host user, and catches everything — with `?` for a home it silently
# falls back to uid 1000. Harmless for a container-to-container deployment, wrong if the workspace
# volumes are bind mounts from a differently-numbered host user. Set the paths above and it is still
# wrong, because `user.home` is not one of them; fixing it needs a config key, not a Dockerfile line.
#
# --- READ THIS BEFORE DEPLOYING: git is part of this service's runtime -----------------------------
# This service is the HOST side of a workspace (README.md, "The boundary"): its work is orchestrating
# containers and git trees that live outside itself. So the runtime stage below is deliberately NOT
# the usual "the binary and nothing else" — one external program is part of its *runtime*:
#
#   git     the gitmirror module forks real git against this service's own mirror of each repository
#           and against the git host over HTTP — `clone --mirror`, `fetch --prune`, `ls-remote`,
#           `worktree add/remove`, `merge`, `merge-tree --write-tree`, `rev-list`, `tag`, and `push`.
#           That is the whole branch lifecycle, not an optional feature: absent, creating a workspace
#           fails at its first `ls-remote`. Installed below from the image's own UBI repositories.
#           Note what it now needs that it did not before: outbound HTTP to `qits.githost.url`. A
#           git host this service cannot reach is no longer a one-endpoint problem.
#
# THERE IS NO DOCKER CLI HERE AND NO SOCKET TO MOUNT. Workspace containers are started by
# qits-containers over HTTP — `QITS_CONTAINERS_URL` plus a machine token — so this service holds no
# docker socket, ships no docker client, and git is the only process it spawns. A deployment that
# still passes `-v /var/run/docker.sock:/var/run/docker.sock` is granting a privilege nothing reads.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the
# first build fetches the image from upstream and caches it, every later build is served from local
# disk. The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when
# quay moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds `service`'s dependencies from source —
# `qits-workspaces-domain` and `qits-workspaces-daemon-protocol` are 1.0.0-SNAPSHOTs this repo never
# publishes (no distributionManagement, no <repositories>), so a builder container with an empty
# ~/.m2 has nowhere else to get them. Copying module by module would mean editing this file every
# time one is added, and forgetting to would fail the build with an unresolved dependency rather than
# anything that names the real cause. `.dockerignore` is what keeps this cheap — without it this line
# drags in every target/ dir, and a stale one can shadow what this stage compiles.
COPY . .

# -Dnative flips quarkus.native.enabled (the `native` profile lives in service/pom.xml, since only
# that module is an application). Cap native-image RAM so the build is deterministic on small
# machines.
#
# `-pl service -am` names the APP module, not a list of modules: it is stable across modules being
# added, because anything `service` needs arrives through `-am` and anything it does not need has no
# business in the image. A module added tomorrow is still COPYed in and still parsed by Maven — it is
# simply not compiled, which is the correct outcome for a module the deployable does not depend on.
#
# Tests are skipped because `./mvnw verify` on a clone is their gate (AGENTS.md) and re-running them
# here proves the same thing more slowly. Note this repo's `native` profile deliberately does NOT
# flip `skipITs` — its *IT.java are the docker-backed `extended` suite — so there is nothing at
# `verify` that `package` misses here.
#
# The two `package-manager-install` flags give this stage a node: the Mandrel builder image ships
# none at all, and Quinoa shells out to npm regardless. They live here rather than in
# application.properties on purpose — a local or CI `mvnw` build must use the node on PATH, so that
# no build silently downloads a toolchain. The version is the PLATFORM pin (22.22.0), not "whatever
# nodejs.org calls current", and it is deliberately not the step container's node: the pipeline's
# image is node-docker-base (node 24) and that is where the bundle is actually built. The two do not
# have to agree, because this stage does not build the bundle.
#
# --- the three command overrides, and why the client is NOT built here ---------------------------
#
# service/src/main/webui is qits-spa-workspaces, and since it adopted QitsMainLayout it depends on
# @qits/ui-components. That package exists ONLY on the platform's own npm registry (qits-artifacts),
# and the SPA commits an .npmrc pointing at the edge's registry vhost — right for a developer on the
# deployment host, and an address a build has never had. npm ranks a project .npmrc above ~/.npmrc,
# and its answer to a registry that never connects is `Exit handler never called!`, its own
# internal-error text, naming neither the registry nor the address. That is what an install here
# looks like when it goes wrong; it is not a hint that anything is retryable.
#
# THE ADDRESS HALF OF THAT ARGUMENT CHANGED AND THE ARRANGEMENT DID NOT. It used to read "a RUN
# reaches the platform registry by NO address" — true while a build ran on the host daemon, and no
# longer true: under the platform builder a RUN executes on qits-net, where the aliases resolve. The
# install still does not belong here, for the reasons that did not move — the committed .npmrc, the
# lockfile's host-published origins that the pipeline rewrites, and a builder stage with no node in
# it — so the bundle stays something the step container produces and this stage only packages.
#
# The bundle is therefore built BEFORE this image, by the first half of the image step in
# .config/qits/ci-event-release-request.yml and ci-event-release.yml, which runs on qits-net where
# the registry is reachable, and arrives in the build context as
# service/src/main/webui/dist/ (see .dockerignore, which stops
# excluding exactly that path for this reason). `--version` is the null command: it proves the
# package manager is really there and does nothing else.
#
# THIS DOES NOT FAIL QUIETLY. The `test -f` below stops a context with no bundle before the
# multi-minute native compile, and Quinoa's own build-dir check would stop it after. A forgotten
# `npm run build` in the step is a red build, not a service that ships without its client. The same
# guard covers the case this comment used to describe on its own: a context whose webui submodule is
# NOT initialised has no bundle either, and stops here rather than at Quinoa's `No package.json
# found in Web UI directory`.
#
# --- and why the bundle is copied onto itself first ----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename
# a directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in
# with `DirectoryNotEmptyException: …/dist/qits-spa-workspaces/browser`, which names the source and
# explains nothing. A builder stage that *creates* dist itself is immune by accident, because dist
# is then already in the upper layer; ours arrives by COPY, so it is not.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
#
# --- where the platform's own Maven artifacts come from -------------------------------------------
# THE DEFAULT IS A HAND BUILD'S, NOT CI's. Both pipelines pass this explicitly, and since the move
# to the platform builder they pass the registry's IN-NETWORK route ($QITS_MAVEN_REGISTRY_URL): a
# `RUN` executes in buildkitd's namespace on qits-net, where the edge vhost below does not resolve.
# The vhost stays the default because a developer's `docker build` on the host is the case it is
# for.
ARG QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL
#
# --- and where Maven Central is mirrored, when it is ----------------------------------------------
# The pipeline passes the platform's pull-through (qits-platform-mirror's central proxy, the same
# authority the FROM lines above pull through); EMPTY — the default — means direct Maven Central.
# `.qits-maven-settings.xml` activates its central-proxy profile only on a non-empty value, so a
# bootstrap or hand build that passes nothing resolves exactly as it always did.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL
#
# --- and how Maven authenticates, once that repository stops answering anonymously ----------------
# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. That is why they are not build args: this image
# is pullable, and a build arg is recorded where anyone who pulls it can read it.
# `.qits-maven-settings.xml` reads the two variables as the `qits-maven-network` server.
#
# FILE FORM, NOT `env=`, AND THAT IS A CHANGE. The `env=` mount has the frontend put the value
# straight into the instruction's environment — convenient, and coupled to a dockerfile-frontend
# version. Under the platform builder the frontend is the BUILDER's to choose rather than this
# file's, and the bare file mount (`/run/secrets/<id>`, read into the one command's own
# environment) is the shape every frontend version serves identically. The variables are set on the
# `./mvnw` invocation alone rather than exported, so their scope is the same one command it was.
#
# Absent secrets leave both variables empty, which is the state every build is in today and the
# state a hand `docker build` is in permanently — and `.qits-maven-settings.xml` reads empty exactly
# as it reads unset. Maven sends credentials only when a repository answers 401, and the registry
# does not yet.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    d=service/src/main/webui/dist/qits-spa-workspaces/browser; \
    test -f "$d/index.html" || { \
      echo "No built client SPA at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)" \
    QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)" \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured on qits-artifacts' first SBOM release
# (2026.902.45658): the service module skips maven-deploy — it ships as this image, not as a jar —
# and the plugin SKIPS deploy-skipping modules by default, with a green build and one INFO line. The
# sbom stage's COPY then fails on a file nothing wrote. The property spelling matters too: the bare
# -DskipNotDeployed=false is silently ignored; only the cyclonedx.-prefixed one reaches the plugin.

# --- sbom -----------------------------------------------------------------------------------------
# The service module's CycloneDX document, exported so the release step can PUT it to the artifacts
# store. A stage rather than a layer of the runtime image, and BEFORE the runtime stage on purpose:
# the last stage is what a plain `docker build` builds, and that must stay the image. The release
# step reaches this one with `--opt target=sbom --output type=local` using the SAME args and secrets
# as the image build, so every layer above is a cache hit in the same builder and the export costs
# seconds. `makeBom` rides
# on the mvnw invocation above rather than a second one, for the same cache reason — one RUN, one
# layer, one truth.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# git-core rather than git: this service runs plumbing, branch and transport commands against its own
# mirrors and the git host, and nothing that needs perl, the extra remote helpers or the docs — see
# the header for why the package is not optional. It is the only program installed here: a docker CLI
# used to come from Docker's own repository beside it, and went with the socket.
RUN microdnf -y --setopt=install_weak_deps=0 --nodocs install git-core \
 && microdnf -y clean all

# `quarkus.package.output-name` names the binary `qits-workspaces` (no -runner suffix), so the
# process is identifiable in `ps`/`docker top` — which matters more here than elsewhere, since this
# host process sits beside a container per workspace and every one of those is also a qits binary.
# That name is spelled a third time in service/pom.xml as failsafe's `native.image.path`; all three
# move together.
COPY --from=build --chown=1001:root /src/service/target/qits-workspaces /work/qits-workspaces
RUN chmod +x /work/qits-workspaces

# The mount point for the data paths in the header, created here so it is OWNED. Docker seeds a
# fresh named volume from whatever the image has at the mount path, ownership included; with no such
# directory it makes one root-owned 0755 and the boot dies on an AccessDeniedException instead of the
# config error, which reads like a bug in this image and is not one. Group-writable and group-root
# because a ubi image is meant to survive being assigned an arbitrary UID with GID 0. (A bind mount
# still takes its ownership from the host — nothing in an image can fix that.)
#
# Creating the mount point is not the same as defaulting a data directory into existence: nothing
# writes here until a deployment has supplied the database triples, and with no volume mounted the
# service still refuses to start.
RUN mkdir -p /data && chown -R 1001:root /data && chmod -R 775 /data

# This service's OWN tree, on the volume mounted at /data: the per-repository git mirrors, the
# worktrees a merge is built in, and the workspace metadata sidecars. Private and rebuildable, and
# deliberately NOT the bare origins qits-artifacts serves — that separation is what makes
# receive-pack the sole writer of every ref in a served repository. Not in the RUN mkdir above: a
# named volume mounted at /data masks whatever the image put there, so the code creates it on first
# use.
ENV QITS_WORKSPACES_DATA_DIR=/data/workspaces

# The event outbox's database WAS named here, and the line is gone rather than moved.
#
# It had to be named while the outbox was a file H2: the qits-eventstream jar's own default rooted
# that file at `${user.home}`, which in this image resolves to `?` (no passwd entry for uid 1001 —
# the header argues that at length), so an unnamed outbox was a boot failure on the next redeploy.
# Naming it here was the fix, and it was an image working around a library's default.
#
# The outbox is a PostgreSQL database of its own now, and the library reads
# QITS_RESOURCE_EVENTSTREAM_URL / _USERNAME / _PASSWORD — injected by qits-deployments from the
# `resources: … postgresql:eventstream:qits_workspaces_eventstream` line in .config/qits/deployments.yml.
# There is no file, no `${user.home}`, and nothing for this image to declare. An ENV here would be
# worse than redundant: it would override the injected credential with a dead one.
#
# The same is now true of this service's own store, which is why no datasource url is set anywhere in
# this file. Both refuse to boot on an unset variable, naming what is missing.

EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net. Note also that the daemon control socket at
# /workspaces/daemon/ must be allow-listed unauthenticated at whatever fronts this: its callers are
# daemons inside workspace containers, holding no user token.
ENTRYPOINT ["/work/qits-workspaces", "-Dquarkus.http.host=0.0.0.0"]
