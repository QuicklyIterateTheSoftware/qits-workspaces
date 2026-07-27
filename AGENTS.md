# qits-workspaces — working notes

Read `README.md` first: it defines the boundary (host side vs. workspace-daemon) and lists the
ports. This file is the working conventions on top of it.

## The one rule that shapes everything

This repo must build and test green from a **clone of itself alone** — no monorepo, no docker, no
prior `mvn install` elsewhere, no credentials. `mvn verify` is the gate. Anything that would break
that is not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why: the poms duplicate versions instead of inheriting them, the daemon protocol is
vendored, tests build their own git origins (`TestOrigin`) instead of using fixtures, and the
container runtime is faked in tests rather than shelling docker.

## Package and module conventions

`eu.wohlben.qits.workspaces.*`, split across two maven modules with disjoint sub-packages so there
is no split package:

- `domain/` — `entity`, `persistence`, `dto`, `mapper`, `control`, `error`. Framework-free in the
  sense that matters: no JAX-RS, no websockets. Entities are Panache active-record with public
  fields; mappers are MapStruct `@Mapper(componentModel = "jakarta")`.
- `service/` — `api` (JAX-RS + SSE, including the raw vertx routes), `daemonhost` (the control
  socket and registry).

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
literal and must carry `/workspaces` itself. Three do, each for its own reason:

- `DaemonControlSocket` — `/workspaces/daemon/{id}`, and a **cross-repo contract**:
  `WorkspaceContainerFactory` injects `ws://<host>:<port>/workspaces/daemon/<id>` as
  `QITS_WORKSPACE_DAEMON_URL` and qits-workspace-daemon dials exactly that. Change both together.
  That literal is also what must be allow-listed unauthenticated at the gateway (`PublicPaths`) —
  the callers are daemons holding no user token. It fails *closed*: a stale allow-list rejects
  daemons loudly rather than exposing anything.
- `ServiceProxyRoute` — `ServiceProxyPath.PREFIX`, `/workspaces/service/`, which is also baked into
  the dev server's `QITS_PUBLIC_BASE` at spawn, so the two cannot be changed apart.
- `CaptureCorsRoute` — reads `quarkus.rest.path` instead of repeating it, because a preflight on a
  different path from the POST it clears is worth nothing, and the client reads a 404 there as "hide
  the button" rather than as an error. `RootPath` normalizes both that key and
  `quarkus.http.root-path`; use it rather than doing the string arithmetic by hand.

`/workspaces/q/*` (openapi, swagger-ui) sits outside `quarkus.rest.path` and moves only with
`quarkus.http.non-application-root-path`. `quarkus.swagger-ui.path` is relative and follows on its
own — do not pin it.

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
the addressability, not the capability. Re-exposing it belongs on the daemon's `WorkspaceApi`, after
`migration-plan.md` §9 item 16.

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
  `RepositoryLookup`. Both were invisible here until the jar was actually run.
- `FakeRepositoryLookup` still wins over `wiring/UnconfiguredRepositoryLookup` with no change on
  your part: the latter is `@DefaultBean`, which yields to any other bean of the type. If you ever
  see the "no RepositoryLookup implementation" warning in a test, a fake is missing rather than
  broken.
- A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
  (`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run first.
- `TestOrigin.create(dataDir)` builds a real bare origin (master + a diverging feature branch) and
  returns a repo id; pair it with `FakeRepositoryLookup.register`.
- The `Fake*` doubles are duplicated between `domain/src/test` and `service/src/test`. That is
  deliberate and matches the monorepo — the two modules do not share a test classpath.
- Integration tests needing real docker, a built `qits/workspace` image and the daemon binary are
  not in this repo. `skipITs=true` is the default; keep `mvn verify` runnable anywhere.
