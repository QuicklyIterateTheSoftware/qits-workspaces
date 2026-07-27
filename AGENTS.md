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

The lineage is `V1` (workspace, workspace_event) then `V2` (service_event,
workspace_bootstrap_run, workspace_prompt_draft, workspace_prompt_attachment). `V1`'s header says
the `V2` tables were deliberately left out; that was true when it was written and is not any more —
`V2`'s header explains why. Extend, never renumber, and never edit an applied file's body: Flyway
checksums it.

Remember that workspace rows are **soft-deleted**. A child table in another context gets no cascade;
publish through `WorkspaceResolved` instead, and fire it synchronously inside the resolving
transaction so observers can join it.

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
resolved — the single role check the system has (`qits.auth.required-role`) is the gateway's. See
`migration-auth-plan.md`.

## Tests

- `service/src/test/resources/application.properties` is **no longer the only copy** of
  `quarkus.rest.path` — `src/main/resources/application.properties` carries it for the packaged
  process. Change one and you must change both; a suite green because the *test* copy is right
  proves nothing about what ships.
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
