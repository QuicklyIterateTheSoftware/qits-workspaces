# qits-workspaces

The **host side** of qits workspaces: the workspace entity and its lifecycle, container
orchestration, host-side git against the bare origin, the workspace-daemon registry, and the routes
over them.

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
| `WorkspaceProcessTracker` | no | the same work runs unnarrated; `technicalProcessId` is null |
| `RepositoryAddressResolver` | no | the daemon id-addresses `/git/<repositoryId>` (its own fallback) |
| `WorkspaceCommandHistory` | no | a workspace's history shows no commands |
| `AgentSessionReporter` | no | `SessionStart` lineage is not forwarded |

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

Moving to the daemon (it already runs in the container, so these become in-container work with a
REST surface reachable through the gateway):

- file access (`/files`, `/files/content`) — today `docker exec find/cat/realpath`
- framework detection and the component map (`/detection`, `/component-map`)
- the bootstrap chain — the daemon already owns execution; only the host's awaiter remained
- prompt drafts and attachments
- `.qits-config.yml` parsing — the daemon's `ConfigParser` is the same parser
- periodic checkpoint push — superseded by the daemon's own auto-push

Staying with their own contexts: repositories, projects, commits and conflict resolution, commands,
agents, telemetry, feature flows, capture, and the technical-process framework.

Still host-side but *should* follow the file/detection work into the daemon: `containerGit` and its
callers (`fast-forward`, `update-from-parent`, `pushBranch`, `isFullyPushed`) still shell git into
the container. Migrating them needs new wire messages on both sides, so it was kept out of the
extraction rather than smuggled into it.

Not covered anywhere yet: **startup reconciliation** of workspaces against the live container set
(containerless-but-live-branch → STOPPED, dangling-volume reaping). That logic lives in the
repositories context's `RepositoryDiscoveryService`, which walks the repositories data dir; this
context has no reconciler of its own.
