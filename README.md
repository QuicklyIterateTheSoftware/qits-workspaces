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

`domain/` is a library jar. **`service/` is the application** — augmented by the
`quarkus-maven-plugin` into a process. `domain` owns its **own datasource, persistence unit and
Flyway lineage** (`db/workspaces/migration`, a separate H2), which is what makes this a standalone
deployable rather than a checkout of the monorepo.

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar

**That second command deliberately fails today**, and the message tells you why: this service has no
`RepositoryLookup`. See "Deploying it" below — it is the one thing between here and a running
process, and it is code rather than configuration.

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

**It does not run yet, and that is on purpose.** `RepositoryLookup` is a mandatory `@Inject` — a
workspace is a branch of a repository, and this context holds no foreign key into the repositories
tables. Nothing implements it. `wiring/UnconfiguredRepositoryLookup` exists only so the module can be
*augmented*: Quarkus resolves injection at build time, so three unsatisfied injection points used to
fail the build outright. That bean satisfies the build and then throws on startup in a production
launch, which is the behaviour the port's javadoc always specified — an unwired deployment dies
immediately instead of 404ing every workspace. In dev and test the check downgrades to a warning, so
`quarkus:dev` and the suite stay runnable.

The fix is an implementation backed by qits-projects over HTTP, at which point that class is deleted
rather than configured.

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
| `/workspaces/api/…` | the JSON API — `workspaces`, `branches`, `history`, `events`, `service-events`, `technical-processes`, `capture` | `quarkus.rest.path` |
| `/workspaces/q/…` | `openapi`, `swagger-ui` — what the framework serves, not application code | `quarkus.http.non-application-root-path` |
| `/workspaces/daemon/{id}` | the daemon's dial-home control socket | `DaemonControlSocket`, literal |
| `/workspaces/service/{id}/{serviceId}/*` | the dev-server reverse proxy | `ServiceProxyPath.PREFIX`, literal |

The last two are second-level segments beside `api` because neither is a JSON API, and both are
literals for the same reason: **raw Vert.x routes and `@WebSocket` paths do not follow
`quarkus.rest.path`.** So is `CaptureCorsRoute`'s preflight, which reads that key rather than
repeating it — a preflight on a different path from the POST it clears is worth nothing. `RootPath`
is where that arithmetic lives.

`/workspaces/daemon/{id}` is a **cross-repo contract**: `WorkspaceContainerFactory` injects
`ws://<qits-host>:<port>/workspaces/daemon/<id>` as `QITS_WORKSPACE_DAEMON_URL` into every container
it creates, and qits-workspace-daemon dials exactly that. `LegacyDaemonControlSocket` still answers
the pre-segment label path for containers provisioned before the move; its own javadoc says why it
keeps no `/workspaces` segment.

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
