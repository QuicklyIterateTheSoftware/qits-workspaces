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
# `docker run --rm qits/workspaces:latest` dies before the HTTP listener with
#
#   Unable to obtain connection from database: A file path that is implicitly relative to the current
#   working directory is not allowed in the database URL
#   "jdbc:h2:file:?/.qits/data/workspaces/h2/workspaces"
#
# That `?` is `${user.home}`. UID 1001 has no /etc/passwd entry in ubi-minimal, and a native image
# resolves user.home through getpwuid(2) rather than $HOME — so setting HOME does not fix it, and
# both of the paths the `domain` jar defaults are `${user.home}`-rooted and have nowhere to land.
#
# An /etc/passwd line for 1001 would make it boot with no environment at all. It is deliberately not
# here, for two reasons that are about this service rather than about images:
#
#   The data is not this service's own. `qits.repositories.data-dir` is a two-way on-disk contract
#   now (projects-volume-decoupling-plan.md BT) — qits-artifacts serves the bare origins over git
#   smart-HTTP, and this service reads legacy sidecars off the same volume. qits-projects no longer
#   mounts it at all: it clones its own mirrors over the wire instead. This service NO LONGER
#   WRITES to the shared volume either way. Every branch create, branch delete, merge and release is
#   a push to the git host over HTTP now, because a filesystem ref update fires no post-receive and
#   so produced no CI run and no event for any of them. The volume stays mounted for two shrinking
#   reasons: a rollback to a previous image needs it, and the workspace metadata sidecars written
#   before they moved are still read from it. Neither will be true forever.
#
#   The H2-on-local-disk arrangement is a prototype, not a decided data architecture. An image that
#   boots out of the box and starts writing a database bakes that prototype in and makes it look
#   settled.
#
# The two are NOT the same kind of state, and this image treats them differently on purpose:
#
#   the git tree      is settled, and nothing about it is stored in SQL. So the image names it
#                     itself — QITS_REPOSITORIES_DATA_DIR=/data/repositories in the runtime stage —
#                     and it is correct out of the box rather than resolving to `?`. The literal is
#                     shared: qits-artifacts ships the identical path, so one mounted volume
#                     satisfies the two-way contract without a deployment having to get the same
#                     string right twice.
#
#   this service's    the git mirrors (one bare per repository, plus the worktrees a merge runs in)
#   own tree          and the workspace metadata sidecars. Named here too —
#                     QITS_WORKSPACES_DATA_DIR=/data/workspaces — because it is a private cache on
#                     the volume this image already owns, with no contract to agree with anybody
#                     about. It is rebuildable: delete it and the next request re-clones.
#
#   the H2 database   is the prototype, so it is deliberately NOT named here and the service will
#                     not start until a deployment says where it goes.
#
# So a deployment mounts a volume at /data (which this image owns, see the runtime stage) and names
# the database, plus the one address this service cannot invent:
#
#   docker run -v qits-workspaces-data:/data \
#     -v /var/run/docker.sock:/var/run/docker.sock \
#     -e QUARKUS_DATASOURCE_WORKSPACES_JDBC_URL=jdbc:h2:file:/data/workspaces/h2/workspaces \
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
# --- READ THIS BEFORE DEPLOYING: docker and git are part of this service's runtime -----------------
# This service is the HOST side of a workspace (README.md, "The boundary"): its work is orchestrating
# containers and git trees that live outside itself. So the runtime stage below is deliberately NOT
# the usual "the binary and nothing else", and two external programs are part of its *runtime*:
#
#   docker  `DockerExecutor` shells the CLI named by `qits.workspace.container-runtime` for every
#           `run`/`stop`/`rm`/`inspect`/`exec`, and its `@Observes StartupEvent` creates the three
#           shared volumes and the `qits-net` network on boot. The CLI is installed below — but it is
#           only half of what a deployment has to supply, and the other half is a decision:
#
#             THE DOCKER SOCKET IS NOT IN THIS IMAGE. Run with
#             `-v /var/run/docker.sock:/var/run/docker.sock`, or no workspace can be provisioned.
#
#           That mount hands this container control of the host's docker daemon, which is
#           root-equivalent — a workspace container is a sandbox precisely because it does *not* get
#           the socket, and this service is the thing on the other side of that boundary. Leaving the
#           mount to the deployment keeps it an explicit act. Without it the service still starts and
#           serves: the startup observer logs one WARN per volume and one for the network (it is
#           best-effort by design) and every provision fails with a docker connection error.
#
#           Only docker's CLI is here. `qits.workspace.container-runtime` names the binary, so
#           pointing it at `podman` makes supplying that CLI the deployment's problem.
#
#   git     the gitmirror module forks real git against this service's own mirror of each repository
#           and against the git host over HTTP — `clone --mirror`, `fetch --prune`, `ls-remote`,
#           `worktree add/remove`, `merge`, `merge-tree --write-tree`, `rev-list`, `tag`, and `push`.
#           That is the whole branch lifecycle, not an optional feature: absent, creating a workspace
#           fails at its first `ls-remote`. Installed below from the image's own UBI repositories.
#           Note what it now needs that it did not before: outbound HTTP to `qits.artifacts.url`. A
#           git host this service cannot reach is no longer a one-endpoint problem.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `localhost:8081/quay/…` is qits-artifacts' pull-through mirror of quay.io: the first build fetches
# the image from upstream and caches it, every later build is served from local disk. The tag is
# upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves it.
FROM localhost:8081/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
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
# @qits/ui-components. That package exists ONLY on the platform's own npm registry (qits-artifacts).
# A RUN step in a docker build reaches the public internet — Maven Central, npmjs.org, nodejs.org —
# and reaches the platform registry by NO address: not the qits-net alias, not localhost:8081, not
# host.docker.internal. So the install cannot happen here.
#
# It does not merely fail, it fails unreadably. The SPA commits an .npmrc pointing at
# localhost:8081 (right for a developer on the deployment host), npm ranks a project .npmrc above
# ~/.npmrc, and npm's answer to a registry that never connects is `Exit handler never called!` —
# its own internal-error text, naming neither the registry nor the address. That is what a build
# here looks like; it is not a hint that something is retryable.
#
# The bundle is therefore built BEFORE this image, by the pipeline step in
# .config/qits/ci-post-receive.yml, which runs on qits-net where the registry is reachable, and
# arrives in the build context as service/src/main/webui/dist/ (see .dockerignore, which stops
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
ARG QITS_MAVEN_REPOSITORY_URL=http://localhost:8081/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL
RUN d=service/src/main/webui/dist/qits-spa-workspaces/browser; \
    test -f "$d/index.html" || { \
      echo "No built client SPA at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version

# --- runtime --------------------------------------------------------------------------------------
# `localhost:8081/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM localhost:8081/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# git-core rather than git: this service runs plumbing, branch and transport commands against its own
# mirrors and the git host, and nothing that needs perl, the extra remote helpers or the docs — see
# the header for why neither package is optional. The docker CLI comes from Docker's own repository because no UBI repository carries a
# docker-compatible client at all; the repo definition is removed again afterwards so the shipped
# image does not carry a third-party package source it has no reason to consult.
RUN printf '[docker-ce-stable]\nname=Docker CE Stable\nbaseurl=https://download.docker.com/linux/centos/9/$basearch/stable\nenabled=1\ngpgcheck=1\ngpgkey=https://download.docker.com/linux/centos/gpg\n' \
      > /etc/yum.repos.d/docker-ce.repo \
 && microdnf -y --setopt=install_weak_deps=0 --nodocs install git-core docker-ce-cli \
 && microdnf -y clean all \
 && rm -f /etc/yum.repos.d/docker-ce.repo

# `quarkus.package.output-name` names the binary `qits-workspaces` (no -runner suffix), so the
# process is identifiable in `ps`/`docker top` — which matters more here than elsewhere, since this
# host process sits beside a container per workspace and every one of those is also a qits binary.
# That name is spelled a third time in service/pom.xml as failsafe's `native.image.path`; all three
# move together.
COPY --from=build --chown=1001:root /src/service/target/qits-workspaces /work/qits-workspaces
RUN chmod +x /work/qits-workspaces

# The mount point for the two data paths in the header, created here so it is OWNED. Docker seeds a
# fresh named volume from whatever the image has at the mount path, ownership included; with no such
# directory it makes one root-owned 0755 and the boot dies on an AccessDeniedException instead of the
# config error, which reads like a bug in this image and is not one. Group-writable and group-root
# because a ubi image is meant to survive being assigned an arbitrary UID with GID 0. (A bind mount
# still takes its ownership from the host — nothing in an image can fix that.)
#
# Creating the mount point is not the same as defaulting a data directory into existence: nothing
# writes here until a deployment has named both paths, and with no volume mounted the service still
# refuses to start.
RUN mkdir -p /data/repositories && chown -R 1001:root /data && chmod -R 775 /data

# The git tree, named here rather than left to a deployment: it is not the prototype, and nothing in
# it is stored in SQL. `/data/repositories` is a literal qits-artifacts also ships — the two-way
# on-disk contract in the header only holds if both resolve it to the same volume, and two services
# each told the same string separately is two chances to get it wrong. qits-projects no longer
# ships this literal at all (projects-volume-decoupling-plan.md BT).
#
# Note what is NOT set beside it: no QUARKUS_DATASOURCE_WORKSPACES_JDBC_URL default. That asymmetry
# is the whole point — the git tree works out of the box, the prototype database refuses to.
ENV QITS_REPOSITORIES_DATA_DIR=/data/repositories

# This service's OWN tree, on the volume mounted at /data: the per-repository git mirrors, the
# worktrees a merge is built in, and the workspace metadata sidecars. Private, rebuildable, and
# deliberately not the tree above — that separation is what makes receive-pack the sole writer of
# every ref in a served repository. Not in the RUN mkdir above for the same reason the H2 directory
# is not: a named volume mounted at /data masks whatever the image put there, so the code creates
# it on first use.
ENV QITS_WORKSPACES_DATA_DIR=/data/workspaces

# The event outbox's database, and it IS named here, which looks like the opposite of the line above
# until you ask whose data it is. It is not this service's: it is the qits-eventstream library's
# private durability for events it has accepted but not yet delivered, with its own schema and its
# own lineage, and this service reads none of it. There is no data-architecture decision to leave
# open, so there is nothing for a deployment to declare on purpose.
#
# And leaving it unset is not "refuses to start" here, it is "refuses to start LATER": the jar's own
# default roots the file at `${user.home}`, which in this image resolves to `?` (no passwd entry for
# uid 1001 — the header argues that at length), so an unnamed outbox is a boot failure on the next
# redeploy rather than a prompt to configure something. A service whose release door is what
# publishes the event must not be the service the event's plumbing bricks.
#
# H2 creates the directory itself; it is not in the RUN above because a named volume mounted at /data
# masks whatever the image put there, so a mkdir would only help the first container ever to run.
# A deployment that wants the outbox elsewhere still overrides QUARKUS_DATASOURCE_EVENTSTREAM_JDBC_URL
# exactly as qits-ci's does.
ENV QUARKUS_DATASOURCE_EVENTSTREAM_JDBC_URL=jdbc:h2:file:/data/eventstream/h2/eventstream

EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net. Note also that the daemon control socket at
# /workspaces/daemon/ must be allow-listed unauthenticated at whatever fronts this: its callers are
# daemons inside workspace containers, holding no user token.
ENTRYPOINT ["/work/qits-workspaces", "-Dquarkus.http.host=0.0.0.0"]
