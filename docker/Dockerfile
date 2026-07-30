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
#   The data is not this service's own. `qits.repositories.data-dir` is a three-way on-disk contract
#   — qits-projects clones the bare origins into it, qits-artifacts serves them over git smart-HTTP,
#   and this service branches from and merges into them. All three must resolve it to the SAME
#   volume. A container that came up healthy on an ephemeral private copy is the failure that hides;
#   one that refuses to start is the failure you see.
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
#                     shared: qits-artifacts and qits-projects ship the identical path, so one
#                     mounted volume satisfies the three-way contract without a deployment having to
#                     get the same string right three times.
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
#   git     `GitExecutor` and `WorkspaceService` fork real git against the per-repo bare origin —
#           `branch`, `worktree add/remove`, `merge`, `merge-tree --write-tree`, `show`, `rev-parse`,
#           `branch -D`. That is the whole branch lifecycle, not an optional feature: absent, creating
#           a workspace fails at the first `git branch`. Installed below from the image's own UBI
#           repositories.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
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
# The two `package-manager-install` flags are how the Angular client gets built HERE. Quinoa shells
# out to npm, and a Mandrel builder image has no node — so without them this stage fails the moment
# the webui submodule is present, and the flags are on the command line rather than in
# application.properties precisely because a developer machine already has node and should keep using
# its own. The version is pinned so the image build is reproducible, not "whatever nodejs.org calls
# current".
#
# A build context whose webui submodule is NOT initialised fails this stage with `No package.json
# found in Web UI directory: 'src/main/webui'` — git leaves the gitlink as an empty directory, and an
# empty directory is the one case Quinoa treats as a misconfiguration rather than as "no client".
# Loud is the right failure here, but it is a failure: README.md says so, and .config/qits/
# ci-post-receive.yml initialises the submodule before this build for exactly that reason.
RUN ./mvnw -B -ntp -pl service -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0

# --- runtime --------------------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6
WORKDIR /work

# git-core rather than git: this service runs plumbing and branch commands against a bare origin and
# nothing that needs perl, the remote helpers or the docs — see the header for why neither package is
# optional. The docker CLI comes from Docker's own repository because no UBI repository carries a
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
# it is stored in SQL. `/data/repositories` is a literal qits-artifacts and qits-projects also ship —
# the three-way on-disk contract in the header only holds if all three resolve it to the same volume,
# and three services each told the same string separately is three chances to get it wrong.
#
# Note what is NOT set beside it: no QUARKUS_DATASOURCE_* default. That asymmetry is the whole point
# — the git tree works out of the box, the prototype database refuses to.
ENV QITS_REPOSITORIES_DATA_DIR=/data/repositories

EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net. Note also that the daemon control socket at
# /workspaces/daemon/ must be allow-listed unauthenticated at whatever fronts this: its callers are
# daemons inside workspace containers, holding no user token.
ENTRYPOINT ["/work/qits-workspaces", "-Dquarkus.http.host=0.0.0.0"]
