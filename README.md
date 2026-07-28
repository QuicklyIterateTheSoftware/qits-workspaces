# qits-workspaces

The **host side** of qits workspaces: the workspace entity and its lifecycle, container
orchestration, host-side git against the bare origin, the workspace-daemon registry, dev-server
supervision, the bootstrap-chain runner, the technical-process framework, prompt composition,
feature capture, and the routes over all of it — at `/workspaces`, its gateway segment.

A workspace is a branch ref in a repository's bare origin **plus** a per-workspace container that
clones that branch into `/workspace`. This repo owns everything about that from the host's side of
the boundary. Everything that runs *inside* the container belongs to
[qits-workspace-daemon](https://github.com/QuicklyIterateTheSoftware/qits-workspace-daemon).

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker

## Layout

| Module | What |
|---|---|
| `domain/` | `eu.wohlben.qits.workspaces.*` — entity, persistence, dto, mapper, control, and the framework-free SPIs the daemon implements. No web, no JAX-RS. |
| `service/` | `eu.wohlben.qits.workspaces.{api,daemonhost}` — JAX-RS routes, the SSE channels, and the daemon control socket + registry. |
| `workspace-daemon-protocol/` | A **vendored copy** of the daemon wire contract. See that module's pom for why. |

`domain/` is a library jar. **`service/` is the application** — it carries
`<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as a native binary.
`domain` owns its **own datasource, persistence unit and Flyway lineage**
(`db/workspaces/migration`, a separate H2), which is what makes this a standalone deployable rather
than a checkout of the monorepo.

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
| `RepositoryAddressResolver` | no | the daemon id-addresses `/git/<repositoryId>` (its own fallback) |
| `WorkspaceCommandHistory` | no | a workspace's history shows no commands |
| `AgentSessionReporter` | no | `SessionStart` lineage is not forwarded |
| `WorkspaceTerminalSessions` | no | the interactive service terminal refuses the upgrade; the live log is unaffected |
| `WorkspaceChatInbox` | no | service events are spooled instead of delivered — the same path as "no chat is running" |
| `WorkspaceProcessTracker` | *implemented here* | `TechnicalProcessRegistry` is the default; the port stays so an application can substitute its own |

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
convention qits-projects already uses. Quarkus reads `.env` from the process's **working
directory** at config ordinal 295 — above the packaged `application.properties` (250), below real
environment variables (300) and `-D` (400) — so it overrides the shipped config without a rebuild
and without touching a tracked file, and it works for the native binary exactly as for the
fast-jar. Run from the repo root and it is found. Keys are environment-variable names
(`QITS_PROJECTS_URL`, `QUARKUS_HTTP_PORT`), which is the one cost over a properties file and also
the payoff: the same spellings work unchanged as real env vars in a container. qits-projects
carries a matching example putting it on :8090, so the pair starts side by side with no arguments
at all.

The url carries no path because qits-projects serves its own `/projects/api` segment, so the same
base address works whether the call goes direct or through the gateway. Both services must also
resolve `qits.repositories.data-dir` to the same tree — running them under one `$HOME` does that for
free; containers need the same volume.

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
And `qits.repositories.data-dir` is a shared on-disk contract: qits-projects clones into the same
tree and qits-artifacts serves it over git smart-HTTP, so all three must resolve it to one volume.

## Where it answers

Everything this service serves is under `/workspaces`, its gateway segment. qits-gateway routes
**verbatim by prefix** — `/workspaces/*` reaches `qits-workspaces`, with no rewriting — so the
service serves the prefixed path itself and there is no unprefixed form, on the gateway or on
`qits-net`. Anything left at the root is simply unreachable.

| Prefix | What | Set by |
|---|---|---|
| `/workspaces/api/…` | the JSON API — `workspaces`, `branches`, `history`, `events`, `service-events`, `technical-processes`, `capture` | `qits.rest.path`, which `quarkus.rest.path` is derived from |
| `/workspaces/q/…` | `openapi`, `swagger-ui` — what the framework serves, not application code | `quarkus.http.non-application-root-path` |
| `/workspaces/daemon/{id}` | the daemon's dial-home control socket | `DaemonControlSocket`, literal |
| `/workspaces/service/{id}/{serviceId}/*` | the dev-server reverse proxy | `ServiceProxyPath.PREFIX`, literal |
| `/workspaces/container/{id}/*` | the workspace-daemon reverse proxy | `ContainerProxyPath.PREFIX`, literal |
| `/workspaces/daemon/stream/{nonce}` | where a daemon's tunnel dial-back lands | `WorkspaceTunnels.STREAM_PATH_PREFIX`, literal |

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
deleted; the daemon's `WorkspaceApi` is where the endpoints belong, once
`migration-plan.md` §9 item 16 gives it a gateway route and an API token.

**What was removed is the externally addressable surface, not the capability.** The
provision → bootstrap → services sequence is host-orchestrated and untouched: `WorkspaceBootstrapRunner`
still runs the chain on `WorkspaceContainerStarted` and fires `ReadyForServices`, `ServiceLifecycleCoupler`
still auto-starts services on it, and `ServiceProxyRoute` still reads `ServiceSupervisor` state to
resolve a port. Both host projections stay too — `service_event` with its SSE feed, and
`workspace_bootstrap_run`, which as a result now has **no reader**; `BootstrapRun`'s javadoc records
that and what to do about it.

Still host-side but *should* follow the file/detection work into the daemon: `containerGit` and its
callers (`fast-forward`, `update-from-parent`, `pushBranch`, `isFullyPushed`) still shell git into
the container. Migrating them needs new wire messages on both sides, so it was kept out of the
extraction rather than smuggled into it.

Not asserted anywhere any more, dropped when their setup could not come along: the
`CommandRegistry` PTY attach path (`ServiceAttachTerminalTest`), the delivery half of the agent sink
(now `WorkspaceChatInbox`'s contract), the repository-delete cascade onto `workspace_bootstrap_run`
(it starts in another database), and the depth-2 submodule closure
(`WorkspaceSubmoduleProvisionTest` — its fixtures belong to qits-projects).

Not covered anywhere yet: **startup reconciliation** of workspaces against the live container set
(containerless-but-live-branch → STOPPED, dangling-volume reaping). That logic lives in the
repositories context's `RepositoryDiscoveryService`, which walks the repositories data dir; this
context has no reconciler of its own.
