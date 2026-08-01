# qits-workspaces — working notes

Read `README.md` first: it defines the boundary (host side vs. workspace-daemon) and lists the
ports. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. `mvn verify` is the gate. Anything that would break that is
not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why: the poms duplicate versions instead of inheriting them, the daemon protocol is
vendored, tests build their own git origins (`TestOrigin`) instead of using fixtures, and the
container runtime is faked in tests rather than shelling docker.

*A clone*, not a bare `git clone`: two submodules are reactor input and the build needs both.

    git submodule update --init

`eventstream/` is the qits-eventstream repository, listed as a `<module>` and built in place, so an
uninitialised one is a missing pom and the reactor dies before it compiles anything — a failure that
names a path and not a submodule. `service/src/main/webui/` is the SPA and stops Quinoa instead. No
credentials either way: both are public. `.config/qits/ci-post-receive.yml` initialises the two by
path in the image build for the same reason, and a third submodule added for the SPA's sake would
have to be added there deliberately.

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
  without the release flow noticing.
- `domain/` — `entity`, `persistence`, `dto`, `mapper`, `control`, `error`. Framework-free in the
  sense that matters: no JAX-RS, no websockets. Entities are Panache active-record with public
  fields; mappers are MapStruct `@Mapper(componentModel = "jakarta")`.
- `service/` — `api` (JAX-RS + SSE, including the raw vertx routes), `daemonhost` (the control
  socket and registry), `bus` (the event-bus wiring), `wiring`, `security`.
- `workspaces-events/` — `events`, and nothing else. Plain records on `qits-eventstream`, no CDI:
  this is the vocabulary a *consumer* depends on, which is why it is not a package inside `domain`.
  `domain` does not depend on it — the domain's seam is a port, and the bus stays in the deployable.

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

The lineage is `V1` (workspace, workspace_event), `V2` (service_event, workspace_bootstrap_run,
workspace_prompt_draft, workspace_prompt_attachment) then `V3` (one active workspace per branch).
`V1`'s header says the `V2` tables were deliberately left out; that was true when it was written and
is not any more — `V2`'s header explains why. Likewise `V1`'s "no unique constraint" note is
superseded by `V3` for the branch. Extend, never renumber, and never edit an applied file's body:
Flyway checksums it.

**H2 has no partial indexes.** `create unique index … where …` is a syntax error, so a rule that
only applies to some rows carries its predicate in a generated column instead and relies on unique
indexes ignoring NULLs — see `V3`'s `active_branch`. Verify any index syntax against the H2 version
actually on the classpath before writing the migration; it is the only target.

Remember that workspace rows are **soft-deleted**. A child table in another context gets no cascade;
publish through `WorkspaceResolved` instead, and fire it synchronously inside the resolving
transaction so observers can join it.

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

`/workspaces/<second level>/…`, always. The gateway routes **verbatim by prefix**, so the service
serves the prefixed path itself; there is no unprefixed form, on the gateway or on `qits-net`, and
anything left at the root is unreachable. `README.md` has the table.

The one thing to know before you add a route: **`quarkus.rest.path` moves the JAX-RS routes and
nothing else.** A raw Vert.x route or a `@WebSocket` path registers straight onto the router with a
literal and must carry `/workspaces` itself. Five do, each for its own reason:

- `DaemonControlSocket` — `/workspaces/daemon/{id}`, and a **cross-repo contract**:
  `WorkspaceContainerFactory` injects `ws://<host>:<port>/workspaces/daemon/<id>` as
  `QITS_WORKSPACE_DAEMON_URL` and qits-workspace-daemon dials exactly that. Change both together.
  That literal is also what must be allow-listed unauthenticated at the gateway (`PublicPaths`) —
  the callers are daemons holding no user token. It fails *closed*: a stale allow-list rejects
  daemons loudly rather than exposing anything.
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
  Note also that **`vertx-http-proxy` skips its interceptor chain on a WebSocket upgrade**, so the
  bearer has to be set on the inbound request for the two interactive sockets
  (`presentBearerOnUpgrade`). Both interceptors are dead on that path — the same defect the gateway
  works around in `EdgeHeaders.applyToUpgrade`, and a stub origin that accepts any handshake will
  not show it.
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

**Every one of those literals has to be repeated in `quarkus.quinoa.ignored-path-prefixes`, and
that is the one place a new route here is easy to forget.** Quinoa's SPA fallback is registered last
and rewrites anything under `/workspaces` it is not told to skip; the skip list it *derives* holds
only `quarkus.rest.path` and `quarkus.http.non-application-root-path`, so the raw routes above are
outside it. Measured on the packaged fast-jar before the key was set, `GET /workspaces/daemon`,
`/workspaces/daemon/` and `/workspaces/daemon/{id}` — a plain GET, since websockets-next claims only
the upgrade — each answered **200 `text/html`** with the SPA's `index.html`. A machine client parses
a web page as data; the correct answer is a 404. Setting the key **replaces** the derivation instead
of extending it, which is why the list spells `/api` and `/q` out again, and the values are
**relative** to `ui-root-path` (`/api`, never `/workspaces/api` — an absolute value matches nothing
and looks exactly like an unset key). `application.properties` carries the reasoning; add a literal
route there and here in the same commit. A sibling service whose whole machine surface is
`quarkus.rest.path` plus the non-application root would leave the key unset and let the derivation
track the paths — none of the five qualifies today (each carries an MCP root, a git host or a daemon
socket outside the derivation, and qits-ci's own `/ci/daemon` was measured falling through to the
SPA the same way), but the rule stands for the next one.

**A workspace is not a sub-resource of a repository.** This context holds a repository id as a
string, in another database, with no FK — so collections filter by `?repositoryId=` and an item is
`{id}` alone. `/branches/{merge,cleanup}` take `?repositoryId=` too: a branch has no id of its own,
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

One consequence, recorded so it is not rediscovered: **`workspace_bootstrap_run` now has no
reader.** `WorkspaceBootstrapRunner` writes it and nothing queries it — `WorkspaceHistoryService`
does not. The table stays (dropping it is a data migration), but durable history nobody can query is
not history; the workspace history surface is the obvious home for a reader. `BootstrapRun`'s
javadoc carries the full note.

Still keyed on the label, deliberately: `service_event.workspace_id` — diagnostic history that
outlives the row, see `V2`'s header.

Resolving an id costs a query, which matters on the **SSE routes**: a `Multi`-returning method runs
on the IO thread, so a lookup in one throws `BlockingOperationNotAllowedException` (a 500) unless
the method is `@Blocking` — see `WorkspaceEventsController`. Ordinary JAX-RS methods are dispatched
to worker threads already and need nothing.

## The vendored protocol module

`workspace-daemon-protocol/` is a copy of the daemon repo's module, same java package, different
artifactId. Any change to it must be mirrored in
[qits-workspace-daemon](https://github.com/QuicklyIterateTheSoftware/qits-workspace-daemon) and bump
`DaemonProtocol.CAPABILITY_VERSION`. `DaemonCodecTest` runs on both sides and is what catches drift.

## The version bump engine

`VersionStamp` + `VersionBumper` in `domain/…/control/` write a release version into a checkout.
Pure: they read and write files under one directory and touch nothing else, which is what lets the
release flow call them inside a detached worktree before any ref has moved. Only a release calls
them; a plain integrate never reaches this code.

`VersionStamp.of(Instant)` is `YYYY.MMDD.HHMMSS` — `2026.731.193059` — computed as **integer
arithmetic in UTC**, so no identifier can carry a leading zero. That is the whole point of the
shape: the requested `$year.$month.$day-HHMMSS` is invalid semver outright, and its obvious repair
is valid at 19:30:59 and *invalid* at 09:30:59, a bug that passes every daytime test.

**Both bumpers splice character spans into the original text; neither ever round-trips a tree.** A
re-serialized pom reformats the whole file, and a re-serialized `package-lock.json` reorders
thousands of lines including the `resolved` URLs this platform pins deliberately. The one thing to
know before touching `PomVersions`: **the JDK's StAX `Location.getCharacterOffset()` is exact only
inside the scanner's first 8192-character buffer** — measured, 231 of 2857 start elements wrong
across the platform's 45 poms, and every real service pom is bigger than one buffer. Line/column
was exact for all 2857 and is what the locator uses. Jackson's char offsets have no such caveat
(74,075 string values, all exact), but its string values are decoded lazily, so `currentLocation()`
is only past the literal *after* `getText()` has been called.

What moves: every reactor pom's own `<version>` and its in-reactor `<parent><version>` — one
element per pom, six for a five-module reactor — plus any *literal* in-reactor dependency version,
of which the platform has zero (all 20 are `${project.version}`). The reactor is walked by
`<module>`, never by directory scan, which is what keeps `.claude/worktrees/` out without an
exclusion list. On the npm side exactly three fields: `package.json`'s `version` and the lock's
`.version` and `.packages[""].version`, the three `npm ci` compares. `projects/*/package.json` is
bumped too — for the publishable library repos that inner manifest is the released one.

A missing or unparseable manifest **fails loudly**. A bump that silently skips a file ships a
release whose artifacts still carry the previous version, and that is discovered much later.

## The mirror, and what it replaced

Nothing here opens `qits.repositories.data-dir` any more. Each repository is **mirrored** under
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
  discard are `push :refs/heads/<branch>`, a merge is a worktree on the mirror plus
  `push HEAD:refs/heads/<target>`, a release is that plus the tag. There is no other door, which is
  the property the whole change exists to establish.

Only the default branch's push carries `-o qits.release`; nothing else writes the default branch, so
nothing else needs an option. The mirror is a **cache**: delete it and the next request re-clones.

## The two doors: release and integrate

**`POST /workspaces/api/workspaces/{id}/release`** is **the one door into a repository's default
branch**. It merges the workspace's branch into that branch, stamps a fresh `YYYY.MMDD.HHMMSS`
version into the same index, commits both as **one** commit — `release(<version>): <summary>` —
**pushes** it — together with an annotated **tag** named the version — and publishes `SCMRelease`.

**`POST /workspaces/api/workspaces/{id}/integrate`** lands a workspace on **its parent branch** — a
`task/…` on the `epic/…` it forked from — as one pushed commit `integrate(<source>): <summary>`. No
stamp, no bump, no `qits.release` push option, **no tag**, no event, and no `version` in the
response. A workspace whose parent *is* the default branch is refused with `RELEASE_REQUIRED` and
sent to `/release`, because that is the only door that may write it.

**They are two processes and one method.** `ReleaseIntegrator.land(Run)` takes a `Mode`, and the
mode is the whole difference: stamp-and-bump, the commit subject, the push option, the tag.
Everything below
is shared by construction rather than by two implementations agreeing to keep matching — which is
what makes "integrate is as safe as release" a fact instead of a claim. The flow is keyed by
**(repository, source branch)**, worktree name included, so a branch-keyed sibling endpoint is a thin
resolver over the same method rather than a second copy.

`WorkspaceService.landOnBranch` is that shared middle — the lease, `land`, the announcement;
`landWorkspace` wraps it with the workspace's guards and its resolution, and the two public methods
are ten lines each and differ only in the target and the mode.

## The third spelling: releasing a branch by name

**`POST /workspaces/api/branches/release?repositoryId=…`** `{branch, summary}` →
`{version, commitSha, branch}`. It really is a resolver: `releaseBranch` picks the target and calls
`landOnBranch` with the arguments the workspace path passes, so nothing about the release is
re-implemented. It answers with the record `/workspaces/{id}/release` answers with — the *same Java
type*, so the two cannot drift into two schemas. What a branch name adds is only what an id already
carried: which workspace claims it, and who deletes it afterwards.

- **A branch an ACTIVE workspace claims is forwarded to `releaseWorkspace`.** Not "resolved the same
  way" — it *is* the workspace-keyed call, so the row ends `INTEGRATED` with its container and volume
  gone. Releasing the ref and walking away would strand a workspace on a branch that just merged.
- **The source branch is deleted on success**, matching the workspace path's cleanup. That is what
  lets the maintenance train's next force-push be a create, which the git host's hook allows.
- 404 for a branch the origin does not have, 400 for the default branch itself, and the 409 family
  unchanged.

The caller this exists for is a pipeline step, not a person: a `maintenance/<upstream>` ref is
force-pushed by a build container, and a workspace is a container lifecycle plus a branch claim plus
a resolution state machine — all wrong-shaped for a ref a pipeline overwrites at will.
`BranchReleaseControllerTest` names its fixture branches that way, slash and all, which is also what
proves the worktree slug survives a branch name that cannot be a directory name.

**Every ref this service moves is moved by a push, and that is the point.** The bare origins used to
be on our own disk, on the volume qits-artifacts serves, so a branch could be created, merged or
deleted by writing the ref — which is exactly what this service did, and it is why **no branch
creation, no merge and no cleanup it ever performed produced a CI run**: a filesystem ref update
fires no `post-receive`. Pushing over HTTP through qits-artifacts makes receive-pack the sole writer
of every ref, so the protection hook sees every release and the existing post-receive → qits-ci →
build chain happens for the ordinary reason. Nothing downstream learns a new trick. The address is
`qits.artifacts.url` behind the `GitHostAddress` port.

Five properties, each of which is why a step is where it is:

- **`git worktree add --detach` on the MIRROR is what makes "no partial state" true.** The merge,
  the bump, the commit and the tag all happen against a `HEAD` that is not a branch, in a repository
  nobody serves, so a conflict, a bump failure or a crash leaves the target branch
  **byte-identical**. A failed run needs no unwind — only a worktree removal, which `MirrorWorktree`
  does on close. The orphaned commit is git's to collect.
- **`git merge --no-ff --no-commit` is what makes bump-and-merge one commit.** `MERGE_HEAD` stays
  set and the index stays staged; the bump writes into that same index; the single `git commit` that
  follows is a two-parent merge that also carries the version change. No amend, no second commit.
- **The push is the compare-and-swap.** A release carries `--push-option=qits.release`, which the
  git host accepts for **fast-forward updates only** — deliberately not force — so two releases
  racing cannot both win. The loser is rejected as non-fast-forward and told to retry. That is why no
  distributed lock exists here. An integrate sends no option and is no less safe: the hook guards the
  default branch alone, and fast-forward-only is receive-pack's property rather than the option's.
  What the compare-and-swap does **not** settle is two releases of this flow: the lease holds across
  the push, so they are sequential and the second is a clean fast-forward. The tag settles those.
- **The tag is the version-uniqueness guarantee**, and it rides the same push — see below.
- **The stamp is taken once**, at step 4, and threaded through. Recomputed per file, a slow bump
  would write two versions into one commit. A plain integrate takes none at all, which is what makes
  "no version" a fact about the flow rather than a field the controller drops.

**The 409s carry a `reason`, and it is additive.** The envelope is still `{"message": …}`; an
`IntegrateConflictException` adds `reason` ∈ `CONFLICT` / `MERGE_CONFLICT` / `NOT_FAST_FORWARD` /
`ALREADY_INTEGRATED` / `PUSH_REJECTED` / `VERSION_ALREADY_RELEASED` / `RELEASE_REQUIRED`, plus
`conflicts` (the conflicted paths) for the two conflict modes. `WorkspacesExceptionMapper` is where
that happens and it is the only type it special-cases. `PUSH_REJECTED` is the git host's protection
hook refusing: **that must surface as a 4xx carrying the hook's own message, never a 500**, because
the message is the only thing on screen that says what to do instead — and it is **not retryable**,
which is how the client treats it, so never reuse the value for a race. `VERSION_ALREADY_RELEASED`
is the opposite case and is why it is not that value: the version's tag already exists, and a retry
a second later simply works. `RELEASE_REQUIRED` is the wrong-door refusal and the only one where
nothing was attempted.

The enum reaches `docs/openapi.yml` through `api/ApiError`, a schema-only record declared on the
`@APIResponse`s and returned by nothing — the mapper still builds the body, because the extra fields
are present only when they apply and a record would write them as explicit nulls.

**`merge` and `branches/merge` 409 with `RELEASE_REQUIRED` when the target resolves to the default
branch**, naming both doors and the workspace id. They keep every other target — merging into a
*parent* branch is what stacked workspaces do all day, which is also what `/integrate` now does with
a push and a lease behind it. **`merge` is not redundant**: it still takes an arbitrary target and
answers with conflicts rather than throwing, and `branches/merge` needs no workspace at all.

One consequence, recorded because it is a real loss rather than an oversight: **a plain branch can no
longer be auto-cleaned up after integration.** A plain branch's cleanup parent is the main branch by
definition (`canCleanupBranch`), so it is eligible only once merged *into* that branch — and that
door is release's, while release is workspace-keyed. Workspace branches still resolve and are still
deleted; that happens inside the flow.

**Three inherited sharp edges this flow had to fix rather than inherit**, and two of them were
platform-wide:

- **Stale worktrees were never pruned** anywhere in this service. A crashed merge left its admin
  registered and the *next* one failed with "already checked out" — a failure outliving the process
  that caused it. Both the integrate flow and `mergeIntoTarget` prune before adding now.
- **`.tmp-merge-<System.currentTimeMillis()>` collides** within a millisecond. The flow's worktree
  is `.tmp-integrate-<slugged source branch>`, unique per repository by construction and the reason
  the flow is keyed by branch rather than by a workspace row.
- **`GitExecutor` had no timeout at all.** Every git call that talks to the git host — the mirror's
  clone and fetch, `ls-remote`, and every push — now carries one
  (`qits.workspace.git.network-timeout-ms`, which widened and replaced
  `qits.workspace.integrate.push-timeout-ms` when the push stopped being the only network call).
  The bound covers the whole call, not just `waitFor` — a transport that connects and then says
  nothing blocks in `readLine()`, so the drain runs on its own thread and `destroyForcibly` is what
  unblocks it. Local git **inside the mirror** keeps its unbounded wait, where a bound would only
  turn slow into broken. The machinery lives in `gitmirror`'s `GitCli`; `GitExecutor` delegates to
  it rather than carrying a second copy.

**`TestOrigin` sets `receive.advertisePushOptions`**, and it is load-bearing rather than tidy. JGit
advertises push options in production; a local `receive-pack` does **not** by default, and `git push
--push-option` fails outright against a server that did not advertise them. Without that line the
fixture would refuse the exact argv that ships. `FakeGitHostAddress` points the mirror at the local
bare and replaces the **transport only** — the clone, the fetch, the `ls-remote`, the push, the ref
negotiation and the fast-forward check are all real, which is what lets `ReleaseControllerTest`
assert the compare-and-swap.

**`GitHostAddress` has two methods returning one string, and the split is why.** `fetchUrl` is asked
by every read; `pushUrl` is asked once, immediately before a push. `FakeGitHostAddress.beforeNextPush`
hangs its staged second writer on the second of those, so it fires at the one instant a race is about
rather than on the mirror's first fetch. A deployment returns the same value from both
(`ConfiguredGitHostAddress` literally does). Staged rather than raced, because a real race is
nondeterministic about which side loses.

## The release tag

A release pushes **two** refs: `HEAD:refs/heads/<main>` and an annotated tag named the version
exactly — `2026.801.63140`, **no `v` prefix**, because it is the same string the manifests, the event
and the image tags carry. The tagger is the release commit's own identity and the tag message is the
release commit's subject line. A plain integrate tags nothing; it stamps no version, so there is no
name for a tag to be.

**It used to need a dance, and it does not any more.** `prepareWorktree` added its worktree **on the
bare origin**, and a linked worktree shares the common ref store — which there was the bare
qits-artifacts serves off the same volume. So `git tag -a` wrote `refs/tags/<version>` straight into
the served repository with no push at all, the push then reported `[up to date]` with **zero receive
commands**, and a failed run left the tag behind in a repository other people read. The workaround
was `tag -a` → `rev-parse` the tag **object** → `tag -d` → push by sha, plus a `finally` that swept
up the ref when a run died mid-dance.

The worktree is on a **mirror** now, which nobody serves, so all three reasons are gone and so are
all three steps:

    git tag -a <version> -m <subject> HEAD
    git push --atomic --porcelain -o qits.release <remote> \
        HEAD:refs/heads/<main> refs/tags/<version>:refs/tags/<version>

**One push, `--atomic`, never `--force`.** One push is one receive-pack, so both commands ride one
pre-receive and one post-receive. Atomic is what makes the pair all-or-nothing: a refused branch (the
default branch moved) leaves no tag, and a refused tag takes the branch update down with it. Without
`--atomic` the tag lands even when the branch is refused — measured.

**That refusal is the version-uniqueness guarantee** (`VERSION_ALREADY_RELEASED`). Nothing checked
version uniqueness before, and `VersionStamp` used to claim the fast-forward push rejected a
same-second tie — it does not, because the lease serializes releases and the second one's push is a
clean fast-forward. A non-forced push cannot overwrite an existing tag ref, so the tag turns the
missing constraint into a property of the SCM. It still fires from **both** ends: the mirror is
refreshed from the git host at step 0, so its tags are the host's tags and `git tag -a` refuses a
name already released before this flow has pushed anything; the push covers a writer who gets there
later.

It is **reachable**, not theoretical. `ReleaseControllerTest`'s two concurrent releases collide most
runs — the lease runs them back to back and a release of a small repository is well under a second,
so both stamp one version and the second is refused. That test asserts the pair of outcomes rather
than "both land", which it did before the tag existed.

**What the `finally` sweeps now is cache hygiene, not a ref.** A run that tagged and then failed to
push leaves the tag in the mirror, where it names nothing anybody can see — but it would refuse this
repository's next attempt at the same version out of a local leftover, so it is dropped. It cannot
erase a real release tag, because a real release tag is on the git host and this deletes only the
copy.

## The SCMRelease event

A **release** publishes `SCMRelease {projectId, repository, branch, version}` the instant the push is
accepted. A plain integrate publishes nothing — an event that fired for both would make "a release
happened" unlistenable, which is the one thing this event exists to be.

**It means source control has this release, and nothing more.** It does not mean an artifact exists:
that statement is qits-ci's own `SoftwareRelease`, published once per artifact when a repository's
release pipeline goes green, and the gap between the two is a whole build. The event here was called
`SoftwareRelease` until 2026-08-01 and was read as a statement about a package, which worked by
timing rather than by design — see the superproject's `scm-release-split-plan.md`. **The wire name is
the class name** (`QitsEvent.signature()` returns the simple class name), so the class rename was the
wire rename; the payload's four fields did not change.

`ReleaseAnnouncer` in `domain/…/control/` is the port; `service/…/bus/SCMReleaseAnnouncer` is
the implementation, so the domain module stays free of the bus and its transport (the `RunAnnouncer`
precedent in qits-ci, copied down to the package name). It is announced **after the push and before
the transaction commits**: the push is irreversible the instant receive-pack accepts it, so a
statement conditional on the transaction would be silent about a release that really happened.

Four things about it are easy to undo by accident:

- **`SCMReleaseAnnouncer` is a `@DefaultBean`.** The suite's `FakeReleaseAnnouncer` must win,
  and two unqualified beans of one type fail the build at `ArcProcessor#validate` — for every test at
  once, not at runtime. Same annotation and same reason as `HttpRepositoryLookup`.
- **`bus/EventWireReflection` is what makes the publish work in a native image.** `CanonicalJson`
  builds its own `ObjectMapper` by hand, so nothing registers the event's reflection metadata for it.
  qits-ci measured both halves of that on deployed binaries: without the targets every publish dies
  with Jackson's "no serializer found" and the event never even reaches the outbox; without the
  mix-in `classNames` entry the payload silently gains `eventId`. A JVM suite cannot see either.
- **The payload is four fields and stays four.** `eventId` and `occurredAt` are record components
  and are excluded by the library's mix-in, which is why they can be components at all;
  `SCMReleaseTest` asserts the exact canonical string.
- **`RepositoryLookup.RepositoryView` widened to `(id, projectId, mainBranch)`** for this and for
  nothing else. `projectId` is nullable: a registry that does not answer with one costs the event a
  field, never the release.

## Authentication

Authentication happens at `qits-gateway`. This service resolves a principal from a trusted header
(`X-Qits-User`, read by `workspaces/security/ForwardAuthMechanism`) and authenticates nothing.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select and no authorization policy here, and roles are deliberately not
resolved — the single role check the system has (`qits.auth.required-role`) is the gateway's, and so
is the choice of scheme: the gateway authenticates with OIDC, fixed at *its* build time, which is
what makes the variant question single-instance instead of one per service.

**`X-Qits-*` is the gateway's reserved namespace, stripped from every inbound request
unconditionally**, so a client cannot forge one. That strip rule is the entire reason the header can
be trusted here — and it is why `ForwardAuthTest` sets the real header rather than reaching for
`@TestSecurity`. The header *is* the contract under test; a test that mocked the identity instead
would pass just as happily against a mechanism that never reads it.

The daemon control socket is the exception that proves the rule. `/workspaces/daemon/{id}` is
token-free by necessity — its callers are daemons inside containers, holding no user token — and it
names its caller with a path parameter, so anything on `qits-net` can claim to be any workspace's
daemon (`migration-plan.md` §9 item 22). Edge auth neither touches nor fixes that.

## Tests

- **App-level config lives in `service/src/main/resources/application.properties`, and the tests
  inherit it.** That file is on the test classpath and Quarkus merges it, so
  `service/src/test/resources/application.properties` holds test-only *overrides* (in-memory H2,
  `flyway…clean-at-start`, `qits.repositories.data-dir` under `target/`) and nothing else. Never
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
  hold: no `AUTO_SERVER` in the shipped datasource url, every nested record of `QitsConfig` and of
  `CaptureResource`'s payloads present in its `@RegisterForReflection(targets)`, and
  `CaptureCorsRoute` reading an application-owned config key. None of them proves a binary works —
  they prevent the silent re-introduction of what booting one already caught.
- **The event bus is dark in `%dev` and `%test`, but its datasource is not.**
  `qits.eventstream.enabled=false` stops publishing and sweeping; Quarkus still opens the connection
  and runs Flyway at boot, so `service/src/test/resources/application.properties` points the
  `eventstream` datasource at in-memory H2. Without it every run would create and migrate a real
  `~/.qits/data/eventstream` and two builds on one host would race for its single-writer file. A test
  that wants the bus real turns it back on for itself against a stub, never against a live
  qits-events.
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
- A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
  (`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run first,
  or pass `-Dquarkus.http.test-port=<free port>` when something else on the machine is using it.
- `TestOrigin.create(dataDir)` builds a real bare origin (master + a diverging feature branch) and
  returns a repo id; pair it with `FakeRepositoryLookup.register`. The suite sets
  `qits.workspaces.data-dir` to a **different** directory from `qits.repositories.data-dir` on
  purpose: that separation is the change under test, and a suite pointing both at one tree would
  prove nothing. It also sets `qits.workspace.git.mirror-freshness-ms=0`, because a fixture that
  changes between two assertions must never be served from a window.
- `gitmirror/`'s own suite (`RepoMirrorTest`) is the one that proves the substrate: clone, fetch,
  prune, `ls-remote` answering while the mirror is stale, a branch create and delete arriving as
  pushes, the worktree merge/commit/tag/atomic-push sequence, and a leftover worktree being pruned
  rather than inherited. No Quarkus, no database — 14 cases, about a second.
- `domain/src/test/resources/version-fixtures/` holds **copies of real manifests** — qits-ci's
  five-module reactor verbatim, comments and all, plus an SPA's `package.json` with a trimmed lock
  and a pnpm library repo. That is a deliberate exception to "tests build their own": the bump
  engine's job is to leave everything it did not mean to touch byte-identical, and a fixture written
  to be convenient cannot prove that. `VersionFixtures.copy` puts one in a `@TempDir`, because the
  bumpers write. The assertion that carries the suite is the round trip: replacing the new version
  back with the old one must reproduce the original file exactly.
- The `Fake*` doubles are duplicated between `domain/src/test` and `service/src/test`. That is
  deliberate and matches the monorepo — the two modules do not share a test classpath.
- Integration tests needing real docker, a built `qits/workspace` image and the daemon binary are
  not in this repo. `skipITs=true` is the default; keep `mvn verify` runnable anywhere.
