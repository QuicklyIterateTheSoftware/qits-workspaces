# qits-workspaces

The **host side** of qits workspaces: the workspace entity and its lifecycle, container
orchestration, host-side git against the bare origin, the workspace-daemon registry, dev-server
supervision, the bootstrap-chain surface, the technical-process framework, prompt composition,
feature capture, and the routes over all of it.

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

Both are library jars, in the shape of the monorepo's `artifacts`/`ci` modules: a consuming Quarkus
application pulls them in and gets the routes. `domain` owns its **own datasource, persistence unit
and Flyway lineage** (`db/workspaces/migration`, a separate H2), which is what makes this a
standalone deployable rather than a checkout of the monorepo.

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

A standalone deployable must allow-list `/api/workspace-daemon/` for unauthenticated access — that
is the daemon's dial-home control socket, and it authenticates by workspace id, not by session. In
the monorepo this lives in `auth/core`'s `PublicPaths`.

Note also that `WorkspaceController` declares `@Path("/repositories/{repoId}/workspaces")` and
`GlobalEventsController` declares `@Path("/events")`. The monorepo still has its own copies of both;
whichever commit first makes it consume this jar must delete them in the same change.

## What is deliberately *not* here

**Already in the daemon**, which is why they are absent here rather than pending:

- file access (`/files`, `/files/content`) — the daemon's `workspace-daemon-files` module, `java.nio`
  where the host shelled `docker exec find/cat/realpath`
- framework detection and the component map (`/detection`, `/component-map`) —
  `workspace-daemon-detection`
- bootstrap chain *execution* — the daemon's `BootstrapRunner`. The host's awaiter, the run record
  and the surface over it are here (`WorkspaceBootstrapRunner`, `workspace_bootstrap_run`)
- dev-server *execution* — the daemon's `ServiceSupervisor`. The host's projection of it, the event
  feed and the proxy are here (`ServiceSupervisor`, `service_event`, `ServiceProxyRoute`)
- `.config/qits/repository.yml` parsing — the daemon's `ConfigParser`. This context holds the shape
  (`QitsConfig`) and reads it back over the socket (`WorkspaceConfigReader`), never the file
- periodic checkpoint push — deleted rather than relocated; the daemon's `OriginSync` pushes per
  commit within ~500ms, so the sweep was redundant

Staying with their own contexts: repositories, projects, commits and conflict resolution, commands,
agents, telemetry and feature flows.

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
