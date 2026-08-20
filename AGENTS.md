# qits-workspaces — working notes

Read `README.md` first: it defines the boundary (host side vs. workspace-daemon) and lists the
ports. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone builds against the platform Maven repository** — no monorepo and no prior `mvn install`.
`qits-eventstream:1.0.0` is resolved from local qits-artifacts; `qits-local-up.sh` publishes it before
this repository enters the pipeline.

That is why: the poms duplicate versions instead of inheriting them, the daemon protocol is
vendored, tests build their own git origins (`TestOrigin`) instead of using fixtures, and the
container runtime is faked in tests rather than reaching a real one.

The SPA is the sole submodule and an image build needs it.

    git submodule update --init

`service/src/main/webui/` is initialised explicitly by the pipeline. qits-eventstream is a normal
Maven dependency and must not return as a gitlink or reactor module.

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
  fields; mappers are MapStruct `@Mapper(componentModel = "jakarta")`. It depends on
  qits-eventstream for the causation persistence trio and nothing else out of that jar — see
  "The causation stamp on every push" for why the boundary reads *seams* rather than *the bus*.
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

**The lineage is one `V1`, and it starts again there.** The four H2 files (`V1` workspace +
workspace_event, `V2` the four host tables, `V3` one active workspace per branch, `V4`
service_event.workspace_row_id) were deleted rather than continued when the store moved to
PostgreSQL: the move is an unwrap and a re-bootstrap, so no postgres database ever ran them and no
`V5__move_to_postgres.sql` had a reader. `V1`'s header records what the translation changed and what
it deliberately kept. **From here the ordinary rule is back**: extend, never renumber, and never edit
an applied file's body — Flyway checksums it.

**`V2__causation.sql` is that rule being followed**, and the first file to follow it: `causation_id`
(qits-eventstream's generic `CausedRow` column) on `workspace` and `workspace_event` — nullable, no
backfill, part of no constraint and never a foreign key, because the event it names lives in
qits-events' store. The decisions behind it are enforced by `ArchRulesTest` in `domain`: every
`@Entity` here implements `CausedRow` or declares `@Uncaused` with its reason in the javadoc, and a
new entity that skips the decision fails the build naming the class. Two in, four out:

- **`Workspace`** — in. Both creation paths (`createWorkspace`, and `CaptureService.capture` behind
  the capture ingest) run on the request thread, so the `CausationStamp` listener reads the REST
  filter's restored scope. Nothing is set as data because nothing crosses an executor.
- **`WorkspaceEvent`** — in, and it is the one that carries the trace. The workspace row says why
  the unit of work exists; a MERGED/RELEASED/INTEGRATED entry answers to whatever asked for *that*
  landing. All six `recordEvent` sites are on the flow's own thread, and the machine caller worth
  tracing is `POST /workspaces/api/branches/release`, driven by a pipeline step.
- **`ServiceEvent`** — out. `ServiceEventPersister` writes on supervisor/scheduler threads with no
  request context (that is what its `@ActivateRequestContext` is for), no scope stands there and no
  cause is in reach as data. The column would be null forever.
- **`BootstrapRun`** — out. An updatable singleton: one row per `(workspace, command)`, overwritten
  by every run. The stamp is insert-only, so it would pin the first run's cause while every column
  beside it moved on.
- **`WorkspacePromptDraft`** — out, twice: the row is only ever written by the native `insert … on
  conflict` upsert, which no `@PrePersist` can see, and it is an updatable singleton besides.
- **`WorkspacePromptAttachment`** — out, following the draft it is the payload of: composition
  state, written one browser paste at a time, deleted wholesale with the draft.

The lesson to carry to the next entity: `CausationScope` is a plain ThreadLocal, so a row written
behind an executor or queue hop has no ambient scope and the stamp records null. Where the cause
exists as data at such a write site, set `causationId` explicitly — qits-ci paid for that one live.

**`V3__commissioned_credential.sql` adds two nullable `text` columns to `workspace`** — the idp
client a container was commissioned with, and its secret. **No `ArchRulesTest` decision to make**:
they are columns on an entity that is already a `CausedRow`, not a new entity, and the rule is about
the entity. The reasoning that *is* worth knowing is in the file's header and in
`WorkspaceCredentials` — why the secret is stored at all, and why the columns are cleared in the same
breath as the revocation rather than after it.

**The target is PostgreSQL 18.4** — the tag `images/qits-oci-postgresql` is built from, and the
version the suites' embedded binaries are, so a migration is proved against the engine it ships on.
Two H2 habits are gone with it: a rule that applies to some rows is a **partial unique index** now
(`create unique index … where …`, which is what `uq_workspace_active_branch` finally is, instead of
the generated column H2 forced), and `clob`/`blob` are `text`/`bytea`. The second is also an entity
rule — see below.

**`@Lob` is banned on this context's entities.** On H2 a `@Lob String` was a clob and the two agreed;
on postgres `@Lob` means a LARGE OBJECT, so Hibernate binds an oid and the insert fails against the
column the migration declares. Spell it `@Column(columnDefinition = "text")` — or `"bytea"` for
bytes — as `Workspace.preamble`, `WorkspacePromptDraft.content`, `ServiceEvent.logExcerpt` and
`WorkspacePromptAttachment.bytes` do.

**Native SQL is postgres SQL.** `WorkspacePromptDraftRepository.upsert` is the only place this repo
writes any, and it is `insert … on conflict (…) do update` — H2's `merge into … key (…)` until the
move.

Remember that workspace rows are **soft-deleted**. A child table in another context gets no cascade;
publish through `WorkspaceResolved` instead, and fire it synchronously inside the resolving
transaction so observers can join it.

## Surviving a postgres cutover

The platform restarts its own postgres, and this service has to still be there afterwards. Two
layers, and they are not interchangeable.

**Layer 1 is three config lines per postgresql datasource**, and all three are needed:
`jdbc.driver=eu.wohlben.qits.db.PatientPgDriver`, `jdbc.validate-on-borrow=true`,
`jdbc.acquisition-timeout=15S`. The driver (qits-db-core) delegates to pgjdbc and **holds** a
connection request while postgres is coming back — refused, unreachable and `57P03` only, so an auth
failure still fails at once. `validate-on-borrow` turns a dead pooled connection into a fresh
creation attempt, which is the thing the driver makes patient, and the acquisition timeout keeps the
pool's waiter alive while it works. Measured: Agroal does **not** spend its acquisition budget on a
failed connection *creation*, so the last two lines alone never held anything.

`DatasourceBaselineTest` (in `service`) fails the build when a datasource is missing a line. It runs
there and not in `domain` because **two** postgresql datasources reach the deployable — `workspaces`
from the domain jar and `eventstream` from the qits-eventstream jar — and only this module sees both.
The eventstream one's three lines currently sit in `service`'s `application.properties` as a
stand-in, because the released jar predates them; the comment there says when to delete them.

**Layer 2 is `DbRetry` at read seams, and it is placement-sensitive.** Layer 1 cannot help a request
whose connection died *after* statements ran; a plain `DbRetry.call` re-runs the block, which is
safe for a read and not for an arbitrary write, so it is explicit and rare. Today there is exactly
one: `ContainerProxyRoute.resolve`, the daemon lookup that
backs the proxy's 404s — the only path to a workspace-daemon's API, so a blip there takes the file
browser, the terminals and the coding-agent surface down and calls a live workspace missing.

Three rules govern where a wrap may go, and the first is why this one is at the caller rather than
inside `DaemonProxyTargets.resolve`:

- **Outside the transaction.** `resolve` is `@Transactional`; retrying inside it would re-run
  statements on a transaction already marked for rollback. The retry has to surround the
  transactional call so each attempt gets a new one.
- **Never inside a `synchronized` monitor.** Several `WorkspaceService` methods are synchronized —
  sleeping in one holds the lock for the whole deadline. Stay away from them.
- **Never split a deliberate multi-op bracket.** Wrap what runs *after* irreversible work, never a
  bare insert: a commit whose ack was lost would be duplicated.

`ContainerProxyDbPatienceTest` proves both halves — a severed connection is retried once and answers,
and a genuine absence still 404s on the **first** attempt, because an absent row is an answer rather
than a failure. Widen the retry to "anything that went wrong" and every unknown id would sit on the
deadline before 404ing.

**Layer 3 is `DbRetry.inNewTx` at write seams**, which is a different method for a real reason:
`call` retries a block, and when the block is a write, a connection that died inside the *commit*
round trip would be retried into a second write. `inNewTx` owns the transaction boundary, so it can
tell the two apart — it retries only a failure **thrown out of the body** that is connection-classed
(Quarkus rolls a failed body back and never commits it, so that position is known), and rethrows
everything the transaction manager reports, a `RollbackException` included. Narayana spells "the
commit failed, outcome unknown" and "rolled back before committing" with the same exception type, so
a rollback it claims is not evidence of anything.

Three things follow, and each is a way to get this wrong:

- **The wrapped method loses its `@Transactional`.** `inNewTx` opens the transaction per attempt;
  an annotation on top of it would only be the interceptor joining the one already open. The wrap
  goes at the public method and the retried unit is a private one.
- **The body is DATABASE-ONLY.** It re-runs, so anything in it that is not a row — a push, a
  container call, an HTTP call, an event — happens once per attempt. Every wrap here fires its
  `WorkspaceChangePublisher` hint *after* the unit, and hands the repository id out of it so the
  hint needs no second query.
- **The unit ends with a `flush()`, or the retry buys nothing.** Hibernate sends a `persist` at
  commit by default, which is precisely the phase `inNewTx` reports rather than repeats. Flushing
  last moves the write into the statement phase, where a lost connection is a certain no-commit.
  The draft's own writes need no flush: they are native `insert … on conflict` / `delete`
  statements, which execute where they stand.

**Four writes are wrapped, and they were chosen for what a lost one costs**, not for being writes:

- **`WorkspacePromptDraftService.saveDraft`** — the autosave, on a debounce while someone types. It
  is also the perfect first adopter: one `insert … on conflict`, idempotent by construction, which
  is why `WorkspacePromptDraft` is `@Uncaused` in the first place.
- **`WorkspacePromptDraftService.deleteDraft`** — delete-if-present, twice, in one transaction
  deliberately: a draft kept with its images gone is a broken composition.
- **`WorkspacePromptAttachmentService.addAttachment`** — a paste, whose bytes nothing else holds a
  copy of. The row id is minted **before** the retried unit so every attempt writes the same primary
  key; generated inside, a retry would be a second row.
- **`BootstrapRunService.recordOutcome`** — bookkeeping after the step already ran in the container,
  and the caller swallows failures by design, so a dropped row is silent. Its thread is the
  registry's single `daemon-sink-dispatch`, never a socket thread or a monitor: a held attempt
  delays the outcomes queued behind it and loses none.

**What is deliberately not wrapped**, beyond the read-seam rules above (which all still hold — stay
outside `WorkspaceService`'s `synchronized` methods, and never split a multi-op bracket):

- **The workflow verbs** — `createWorkspace`, `merge*`, `release*`, `cleanupBranch`,
  `discardWorkspace`. They orchestrate pushes and containers, so their bodies are not database-only
  and re-running one is not a re-run of a write.
- **`ServiceEventService.publish`** and the other fail-soft diagnostics: dropping one is the
  designed behaviour.
- **`updateAttachment` / `deleteAttachment`** — verbs on an image already on screen and already in
  the database. A failed one is the same click again, which is worth less than a wider wrapped set
  costs to review.

`PromptDraftWritePatienceTest` proves the pair, and it is shaped unlike the read test on purpose:
its wound fires *after* the real statement has run, because only that arrangement can tell a retry
that re-executed rolled-back work from one that added a second effect. `prompt_version` is the
witness — the upsert bumps it once per execution, so a save that survived one severed connection
reads **1**. Its second case is a SQLState `23505` violation surfacing on the **first** attempt,
which is the same narrowness assertion the read test makes about an absent row.

The measurements are in the superproject's `db-patience-plan.md`; the doctrine is step 9 of
`docs/project-setup-quinoa-angular.md`.

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
  A commissioned daemon exchanges its own client pair for a qits-workspaces-audience bearer and
  presents it on every upgrade; the endpoint requires `qits:system`. The local/no-IdP topology
  stays anonymous only while the machine-auth rollout gate is off. Do not make this path public to
  repair a failed dial-home: a missing bearer is an integration failure, not a routing exception.
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
  **A WebSocket upgrade does not go through `vertx-http-proxy` at all** — `proxyUpgrade` does it by
  hand. Two reasons, both read out of 4.5.26. The library skips its whole interceptor chain on an
  upgrade, so the bearer never reached the daemon and both interactive sockets answered 401 (the
  same defect the gateway works around in `EdgeHeaders.applyToUpgrade`). And the pipe it then builds
  is bare `a.handler(b::write)` installs with **no `writeQueueFull`, no `pause`, no `drainHandler`**
  and a failure arm that prints `"Handle this case"` and a stack trace — so a chatty dev server on a
  terminal socket piles up in this process's heap. The hand-rolled path pauses and drains in both
  directions (the discipline `DaemonStreamRoute` already had one hop further in) and forwards a
  refused handshake with the daemon's own status instead of a bare 502. It pipes **raw bytes, never
  frames**: re-framing would break the terminal's close semantics.
  A stub origin that accepts any handshake shows neither defect, which is why
  `ContainerProxyRouteTest` rejects on a bad bearer and parks a reader mid-flood.
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

**`workspace_bootstrap_run` spent a release with no reader and now has one**:
`GET /workspaces/api/workspaces/{id}/bootstrap-runs` (`WorkspaceBootstrapRunController`). That is
not the deleted controller returning — it reads a **host** table, while the run verbs stay on the
daemon. Host-owned state gets a host route; nothing forwards. The client joins it against the
daemon's declared `GET /bootstrap-commands` on `bootstrapCommandId`. `BootstrapRun`'s javadoc
carries the full note.

Three more host-owned surfaces landed on the same rule, all plain JAX-RS under `qits.rest.path`:

- `GET /workspaces/api/workspaces/{id}` — the single-workspace read. The same `WorkspaceDto` the
  collection serves, so a detail page opens from a bare id; ACTIVE only, 404 for a resolved one
  (whose live half would be uniformly null — `history/{id}` is where that record stays readable).
- `GET`/`PUT`/`DELETE /workspaces/api/workspaces/{id}/prompt-draft` — the composition the next agent
  run is written in. Database rows, no container, so they work while the workspace is STOPPED.
- `GET`/`POST`/`DELETE /workspaces/api/workspaces/{id}/prompt-attachments[/{attachmentId}]` — its
  images, on their own SSE topic so a debounced text autosave does not re-download every picture.

Still keyed on the label, deliberately: `service_event.workspace_id` — diagnostic history that
outlives the row, see `V2`'s header.

Resolving an id costs a query, which matters on the **SSE routes**: a `Multi`-returning method runs
on the IO thread, so a lookup in one throws `BlockingOperationNotAllowedException` (a 500) unless
the method is `@Blocking` — see `WorkspaceEventsController`. Ordinary JAX-RS methods are dispatched
to worker threads already and need nothing.

## The agent-activity rollup, and why `ENDED` is kept

`WorkspaceDaemonRegistry` caches each daemon's agent-activity reports per `commandId` and rolls them
up per workspace: **BUSY > WAITING > IDLE > ENDED**, else null. `WorkspaceDto.agentActivity` is that
rollup, and it is RUNNING-only and self-healing like `clean` — a disconnect drops the whole
workspace's entries and a reconnect re-reports them.

`ENDED` used to be **evicted on arrival**, which meant the host could never report it. That is not a
missing field, it is a deleted workspace: the agent-activity bar is ordered by when a workspace's
state last changed, so a session that has just stopped belongs at the front — it is the one waiting
for your next prompt — and eviction made it vanish at precisely that moment. It is kept now and
expires on `qits.workspace.agent-activity.ended-ttl-ms` (30 min: survives a reload and a coffee
break, does not still claim a slot hours later).

Two properties worth not simplifying away:

- **The TTL is evaluated on every read**, not only by the sweep, so the rollup is correct the instant
  it passes. `sweepEndedSessions` exists to *announce* the expiry — nothing on the detail page polls,
  so an `AGENT_ACTIVITY` hint is the only way a fade reaches a browser.
- **A live report always wins.** A resume overwrites the same `commandId`'s entry, so the TTL only
  ever governs a session that stayed finished.

`ENDED` is lowest precedence deliberately: a workspace with one finished session and one still idling
has a live conversation in it.

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

Nothing here opens the shared volume of bare origins any more — there is no config key naming it and
a deployment does not mount it. Each repository is **mirrored** under
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
  `push HEAD:refs/heads/<target>`, a release is that plus the tag and then the same commit again
  onto the environment branch. There is no other door, which is the property the whole change exists
  to establish.

Only a release's pushes carry `-o qits.release` — the default branch's, and the promotion that
follows it; nothing else writes the default branch, so nothing else needs an option. The default
branch's push carries a **second** option, `-o qits.no-ci`, when the release also has a deploy
branch to push: one sha on two refs is two builds and only the deploy branch's means anything. The
mirror is a **cache**: delete it and the next request re-clones.

## The two doors: release and integrate

**`POST /workspaces/api/workspaces/{id}/release`** is **the one door into a repository's default
branch**. It merges the workspace's branch into that branch, stamps a fresh `YYYY.MMDD.HHMMSS`
version into the same index, commits both as **one** commit — `release(<version>): <summary>` —
**pushes** it — together with an annotated **tag** named the version — publishes `SCMRelease`, and
**promotes** the same commit onto every deploy branch the repository's own spec declares, which is
what deploys it.

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
`{version, commitSha, branch, promotions[]}`. It really is a resolver: `releaseBranch` picks the target and calls
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
be on our own disk, on the volume the git host serves, so a branch could be created, merged or
deleted by writing the ref — which is exactly what this service did, and it is why **no branch
creation, no merge and no cleanup it ever performed produced a CI run**: a filesystem ref update
fires no `post-receive`. Pushing over HTTP through qits-githost makes receive-pack the sole writer
of every ref, so the protection hook sees every release and the existing post-receive → qits-ci →
build chain happens for the ordinary reason. Nothing downstream learns a new trick. The address is
`qits.githost.url` behind the `GitHostAddress` port.

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
  that caused it. `RepoMirror.worktree` prunes before adding, and removes a surviving directory the
  prune cannot (prune drops the registration, not the files).
- **`.tmp-merge-<System.currentTimeMillis()>` collides** within a millisecond. The worktree is named
  after the **slugged source branch**, unique per repository by construction and the reason the flow
  is keyed by branch rather than by a workspace row.
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

## The promotions: the further pushes, and the ones that deploy

A release pushes the released commit **again**, onto the **entry branch**. That push is what ships:
the deployer registers and deploys an application from a green build on a branch an environment
listens to, so `main` is the integration trunk that builds and the entry branch is what deploys.
Pushing only `main` builds and deploys nothing — the same rule the direct-push escape hatch follows.

- **One branch, and it is the platform's answer.** `qits.workspaces.release.entry-branch`
  (`environment/prod` shipped) names it, and it is the same for every repository: the deploy ref of
  the environment the platform serves from. It was a per-repository list read out of each spec's
  `deploy_branches`, and that was wrong twice — every repository named the same single ref, so it
  was one answer copied into thirteen files, and a release pushed its sha onto *every* entry, so
  three tiers listed would have shipped into all three in the same second. A fan-out, not a ladder.
  Advancing a release up a ladder of tiers is a separate operation over the deployer's environment
  rows and does not exist yet.
- **What the repository still decides is whether it deploys at all**, and it says so by carrying
  `.config/qits/deployments.yml`. No file, no promotion: a library or an SPA deploys from no ref,
  and pushing one for it buys a CI build and a branch nobody reads. Everything *inside* the file is
  the deployer's, and `DeploymentSpecReader` opens none of it — it is a five-line `isRegularFile`
  check now, down from a vendored ~120-line line parser.
- **Separate pushes, in this order, never one atomic push.** The default branch first, then the
  entry branch. A promotion riding along atomically would let a stuck deploy branch refuse the
  *release*, which is the one ref this flow exists to move.
- **The trunk push goes quiet when there is somewhere to promote to.** It carries `-o qits.no-ci` as
  well as `-o qits.release`, so one sha does not build twice — the entry branch's build is the
  release's signal. A repository that promotes nowhere keeps its trunk push CI-hot: there that build
  is the only proof the release is sound. The promotion push is never quiet.
- **Create or fast-forward, never a force.** A push to a ref that is not there is a create, which is
  the ordinary first case for a repository that has never deployed. A non-fast-forward means the
  branch holds something the release is not built on, and it is reported rather than overwritten.
- **The push carries `-o qits.release` like the release push it follows.** A deploy branch is not
  the repository's default ref, so the protection hook does not read it today; the option is
  fast-forward-only at the hook, which is exactly what this push is, so it costs nothing and keeps
  one release one push argv. No second mechanism exists — it is `worktree.push(PushSpec)` again.
- **A blank `entry-branch` disables promotion, and outranks the repository.** The key is both the
  destination and the kill switch; a deployment that must stop writing deploy branches has to be
  able to, and a switch a repository could talk its way past would not be one.
  `ReleasePromotionDisabledTest` holds it against a repository that *does* carry a spec.
- **`ReleaseIntegrator` says where releases land, once, at boot.** Both states it reports are
  otherwise silent: a release that promotes nowhere is a 200 with an empty `promotions`, and an
  entry branch no environment listens to builds and deploys nothing at all.

**A failed promotion is a partial success, not a failed release**, and that decision lives in
`ReleaseIntegrator` (the class javadoc says why, beside the code). By the time it runs receive-pack
has accepted the release: the commit is on the default branch, the tag is on the host, post-receive
has fired and CI is building. Throwing there would cost the caller its version and its sha, skip
`SCMRelease` and leave the workspace ACTIVE on a branch that is already merged — and undo none of the
push. So it is **200 with an `error` on that branch's entry**: the sentence naming what refused it
and which sha to push once the branch is sorted out, logged at ERROR. The precedent is
`deleteLandedBranch`, which is best-effort for the same reason: once the release is in, nothing after
it may pretend it is not.

The response carries `promotions` for it — `{branch, error}`, `error` null when the push landed — on
the record **both** release doors answer with. **A list holding at most one entry**: it stays a list
because the tier ladder will make it plural again, for a reason the old per-repository list never
had. It is empty when the repository carries no spec, when promotion is off, and for **a plain
integrate**, which released nothing to deploy.

**This repository carries its own** `.config/qits/deployments.yml`, so it releases through the
mechanism it implements.

## The causation stamp on every push

The git host publishes an SCM event per ref a push moves — `SCMPublishCommit`, `SCMPublishTag`,
`SCMDeleteBranch`, `SCMDeleteTag` — and it publishes them **under whatever `X-Qits-Causation-Id` the
push carried**, read off the receive-pack request. Every ref this service moves is moved by a push,
so this hop is where a chain would otherwise break: release → push → commit event → CI run → deploy
is one chain only because the producer says what it was doing.

`domain/…/control/PushCausation` is the port and `service/…/bus/EventstreamPushCausation` reads
`CausationScope`, the same split `ReleaseAnnouncer`/`SCMReleaseAnnouncer` already has and for the
same reason: `domain` stays free of the bus's **seams**. `GitMirrorRegistry` injects it as
`Instance<T>` and hands `GitMirrors` a `Supplier<String>`; `RepoMirror.push(cwd, spec)` turns the
answer into `-c http.extraHeader=X-Qits-Causation-Id: <uuid>`.

**The word is SEAMS, not "the bus", and the narrowing was deliberate (2026-08-10).** The
eventstream jar also carries the platform's causation *persistence vocabulary* — `CausedRow`,
`CausationStamp`, `@Uncaused`, three jakarta-persistence-shaped types with no publish, no subscribe
and no wire in them — and `Workspace` and `WorkspaceEvent` implement it, so the jar sits in
`domain/`'s pom now. What the rule still forbids is control flow: no listener, no publisher, no
`EventFrame`, no `QitsEventBus` anywhere in `domain`, and the announcement ports stay ports. The
`PushCausation` port above is untouched by the narrowing — reading the ambient scope to build a
header is a bus seam, and it stays in `service`. What the dependency costs is honest and paid in
the suite: the jar's persistence unit boots in this module's tests too, so
`testdb/EmbeddedPgConfigSource` feeds it a database of its own and the test properties keep the bus
dark — the same consumer contract `service` has always honoured.

Four things are deliberate:

- **It is attached in `push(Path, PushSpec)` and nowhere else.** Both `push` overloads, both
  worktree pushes, `createBranch` and `deleteBranch` all funnel through it, so no call site has to
  remember — and there is no external remote in this service that could pick it up by accident.
- **The header name is a literal in `gitmirror`**, because that module has no Quarkus in it and
  stays that way. `EventstreamPushCausationTest` asserts it equals `CausationHeader.NAME`; nothing
  else connects the two strings.
- **A value that will not parse as a UUID is dropped rather than interpolated.** A cause is
  advisory and must never fail a release, and a newline in an HTTP header would be injection. The
  parse is the check and the sanitiser at once.
- **Absent is a supported configuration.** No implementation, no cause, no header — which is exactly
  how every push behaved before the git host published anything.

Nothing in the suites can assert the header *arriving*: the fixtures are local bares and
`http.extraHeader` is inert over a file transport. `CausedPushWiringTest` asserts the part that is
this repo's — that a push made inside a scope, through the injected registry, still lands — and
qits-githost owns the far end.

## The SCMRelease event

A **release** publishes `SCMRelease {projectId, repository, repositoryName, branch, version}` the
instant the push is accepted. A plain integrate publishes nothing — an event that fired for both
would make "a release happened" unlistenable, which is the one thing this event exists to be.

**It means source control has this release, and nothing more.** It does not mean an artifact exists:
that statement is qits-ci's own `SoftwareRelease`, published once per artifact when a repository's
release pipeline goes green, and the gap between the two is a whole build. The event here was called
`SoftwareRelease` until 2026-08-01 and was read as a statement about a package, which worked by
timing rather than by design — see the superproject's `docs/scm-release-split-notes.md`. **The wire name is
the class name** (`QitsEvent.signature()` returns the simple class name), so the class rename was the
wire rename; the payload's fields did not change.

`ReleaseAnnouncer` in `domain/…/control/` is the port; `service/…/bus/SCMReleaseAnnouncer` is
the implementation, so the domain module stays free of the bus's seams and its transport (the
`RunAnnouncer` precedent in qits-ci, copied down to the package name; "seams" rather than "the bus"
since the causation persistence trio moved in — see the push-causation section above). It is announced **after the push and before
the transaction commits**: the push is irreversible the instant receive-pack accepts it, so a
statement conditional on the transaction would be silent about a release that really happened.

Five things about it are easy to undo by accident:

- **`SCMReleaseAnnouncer` is a `@DefaultBean`.** The suite's `FakeReleaseAnnouncer` must win,
  and two unqualified beans of one type fail the build at `ArcProcessor#validate` — for every test at
  once, not at runtime. Same annotation and same reason as `HttpRepositoryLookup`.
- **`bus/EventWireReflection` is what makes the publish work in a native image.** `CanonicalJson`
  builds its own `ObjectMapper` by hand, so nothing registers the event's reflection metadata for it.
  qits-ci measured both halves of that on deployed binaries: without the targets every publish dies
  with Jackson's "no serializer found" and the event never even reaches the outbox; without the
  mix-in `classNames` entry the payload silently gains `eventId`. A JVM suite cannot see either.
- **`eventId` and `occurredAt` stay out of the payload.** They are record components and are
  excluded by the library's mix-in, which is why they can be components at all; `SCMReleaseTest`
  asserts the exact canonical string, so a field added here is a deliberate edit there.
- **`repositoryName` is the field a committed CI selection can address, and `repository` is not.**
  A row id is whatever the platform instance's registry minted: for a repository the platform
  manifest declares it equals the name, but a repository the projects self-seed reconcile
  registered gets a **UUID**, different on every instance. So
  `repository: { exact: qits-projects-daemon }` in a `.config/qits/ci-event-*.yml` matched nothing
  on this platform, and matched **silently** — CI logs matches and never non-matches, so the two
  daemons' release pipelines simply never fired. Both fields ship: the id for anyone joining back
  to the registry, the name for anyone selecting on it. A selection that names a manifest
  repository keeps matching `repository` and is honest doing so.
- **`RepositoryLookup.RepositoryView` widened to `(id, name, projectId, mainBranch)`** for this and
  for nothing else. `name` and `projectId` are both nullable: a registry that does not answer with
  one costs the event a field, never the release.

## Authentication

Authentication happens at `qits-gateway`. This service resolves a principal from a trusted header
(`X-Qits-User`, read by `workspaces/security/ForwardAuthMechanism`) and authenticates nothing.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select in this service. The shared `qits-auth-core` resolves both
`X-Qits-User` and `X-Qits-Roles`; human-facing REST boundaries use Jakarta
`@RolesAllowed("qits:admin")`. Machine-facing boundaries require an authenticated identity and
retain their narrower `MachineAuth` audience/scope checks.
**`X-Qits-*` is the gateway's reserved namespace, stripped from every inbound request
unconditionally**, so a client cannot forge one. That strip rule is the entire reason the header can
be trusted here — and it is why `ForwardAuthTest` sets the real header rather than reaching for
`@TestSecurity`. The header *is* the contract under test; a test that mocked the identity instead
would pass just as happily against a mechanism that never reads it.

The daemon control socket is the exception that proves the rule. `/workspaces/daemon/{id}` is
token-free by necessity — its callers are daemons inside containers, holding no user token — and it
names its caller with a path parameter, so anything on `qits-net` can claim to be any workspace's
daemon (`migration-plan.md` §9 item 22). Edge auth neither touches nor fixes that.

## The workspace image, and who pulls it

`qits.workspace.image` is a **registry-qualified reference at a pinned calver**, and there is no
`defaultValue` behind it in code — a deployment that loses the key must fail at startup naming it
rather than launch something a host happens to have lying around. The properties file carries the
reasoning; here is what surrounds it.

**There is no hand-built image any more, and no recipe for one.** `qits/workspace:latest` was a
local tag on one machine: no version, no registry, no pipeline, and a platform unwrap swept it,
after which every workspace launch died with "no workspace-daemon dialed home". What publishes the
image is **qits-workspace-daemon's own release pipeline**, which builds two docker artifacts —
`qits/workspace-daemon`, the small one qits-deployments reads, and `qits/workspace`, the released
toolchain with the daemon copied in as its entrypoint. **This pin names the second.** Only that one
is startable as a workspace, and the difference is one hyphen.

**`.config/qits/ci-event-upstream-workspace-daemon.yml` is what moves the pin**, on
**`SoftwareRelease`** — the artifact-published event, not `SCMRelease`, which fires at tag time and
would pin a version whose 3.4 GB image is still uploading or never uploads at all. It selects on
`packageName` rather than on the repository, because `SoftwareRelease` carries a repository ROW ID
and this daemon's is a per-platform UUID; the file says so at length, along with why it probes the
registry's `manifests/<tag>` and never `tags/list`.

**qits-containers pulls it, not this service.** The pin travels in the ensure request's spec —
`containershost/WorkspaceContainers.ensureRequest` — under **`PullPolicy.MISSING`**, which is the
same inspect-then-pull rule the deleted `DockerExecutor.ensureImage` followed, enforced one hop out
where the daemon actually is. A present image is never re-checked against the registry: the
reference is a version, so what is local under that name *is* the release. Three things follow:

- **The bound is the client's, and it is not a key of this repo's.** `qits.workspace.image-pull-timeout-ms`
  is gone with `qits.workspace.container-runtime`; the ceiling is
  `qits.containers.client.ensure-timeout` (PT10M, shipped in the client jar and twenty times the
  other deadline for exactly this reason — the base alone is ~3.4 GB). Setting either deleted key
  sets a key nothing reads.
- **A wrong pin comes back as a refusal, and the launch fails naming it.** The orchestrator answers
  409 `IMAGE_MISSING` (`ContainersAnswer.imageMissing()`), which `WorkspaceContainers.holdThrough`
  deliberately does **not** hold through — only 401/403 and unreachable are about the moment, and no
  retry helps until something pushes the image. That failure is *expected*: the tag is written by a
  release train and the image is published by another repository's pipeline, so the two can
  disagree. What it must never do is hang or fall back to something stale.
- **`WorkspaceContainersTest` is what pins both halves** — the literal spec including
  `PullPolicy.MISSING`, and an `IMAGE_MISSING` refusal surfacing in the thrown message. It runs
  against a stub HTTP origin; no docker and no orchestrator involved.

**Measured 2026-08-10, and worth knowing before you debug a launch:** the platform registry holds no
tag under `qits/workspace`, none under `qits/workspace-daemon` and none under the toolchain base
`qits/workspace-base`. The shipped pin is therefore a **placeholder** naming nothing, and the first
real value will arrive through the train (or by hand at the first published calver). Until then a
workspace launch fails at the orchestrator's pull, which is the loud version of what used to be
silent.

## The credential a workspace container holds

A workspace container gets an **idp client of its own** — commissioned at provision, injected as
`QITS_COMMISSIONED_CLIENT_ID`/`QITS_COMMISSIONED_CLIENT_SECRET`, handed back at teardown. README has
the operator's half; here is what the code decides and why.

**The lifetime is the CONTAINER's, and that is the one thing to keep straight.** Not the row's:
`deleteContainer` leaves an ACTIVE workspace with no credential, and the next ensure commissions a
fresh one. Not a token's either — the pair is what lives long and tokens are re-minted underneath it,
which is what makes "no TTL, no refresh" a property rather than an omission.

**The pair lives on the workspace row, and the container factory looks it up.** It is not handed to
`forWorkspace` as an argument, and the reason is `ContainerRuntime.start`: the orchestrator has no
start verb, so a stopped container is started by presenting its spec **again**, under
`Recreate.ifChanged`. A spec whose environment differs from the running container's is a spec change,
and the orchestrator **replaces** the container — writable layer and all. A credential that arrived
as a parameter on the provision path and was absent on the start path would therefore destroy a
container on every resume. The row makes the spec reproducible at every ensure, which is also why the
**secret** is a column and not something re-fetched: qits-idp hands it out once.
`WorkspaceContainersTest` asserts the two specs differ, which is the same fact from the other side.

**Four seams, and the reason there is no `WorkspaceResolved` observer.** Commissioning is in
`provisionContainer` alone — the fresh arm of `ensureContainer` and, through it, recreate, so nothing
else has to remember. Decommissioning is at three: `doDiscard` (every resolution verb), the
branch-gone abandon in `ensureContainer`, and `deleteContainer`. An observer on `WorkspaceResolved`
would cover the first two and **not** the third, which fires no event at all — so one mechanism would
still need a second call site, and two mechanisms for one rule drift apart. It would also put an HTTP
call inside the resolving transaction, which is fired synchronously so observers can join it. Each
call therefore sits beside `containers.rm`, itself an HTTP call and best-effort for the same reason.

**Commissioning fails a launch; decommissioning never fails a teardown.** The first is patient
(`qits.workspace.commission.patience`, and 401/403 are held through for the idp-cutover reason
`WorkspaceContainers.holdThrough` records), then throws — a workspace with no identity would pull as
nobody and only discover it much later. The second logs and moves on, because everything after an
accepted removal must not pretend it did not happen. **`CommissionReconciler` is what makes that
safe**: hourly and at boot it asks qits-idp what it holds for this service and gives back every
`workspace`-kind row no ACTIVE workspace claims **by client id** — so a recreate's replaced pair and a
crashed teardown's leftover are both orphans the moment they stop being claimed. It only ever deletes
what that listing just returned, and an unreadable listing comes back empty, so a blip reaps nothing
rather than everything.

**Absent is a supported configuration in two spellings and they behave identically**: no
implementation of `CredentialCommissioner`, or one wired against no issuer. The switch is
`quarkus.oidc-client.client-enabled` — the extension's own, read a third time here for the reason
`ContainersClientProducer` reads it a second time. There is no key of ours, and there must not be.

## Admin workspaces: the one privilege a workspace can be granted

An **admin workspace** is an ordinary workspace whose container holds the **host's docker socket**,
so that platform administration can be done from inside a workspace. It is asked for at creation —
`POST /workspaces/api/workspaces` with `admin: true`, which the workspaces SPA offers as a checkbox
on the ad-hoc create form — and it is the only thing about a workspace container that is not the
same for every workspace.

**A container holding that socket is root-equivalent on the host.** Everything below follows from
that one sentence, and each piece is a place the property could be lost:

- **The posture is a column on the row** (`Workspace.admin`, `V4`), not a flag on a launch. The
  orchestrator has no start verb — a stopped container is started by presenting its spec *again*
  under `Recreate.ifChanged` — so a posture that arrived as an argument on the provision path and
  was missing on the start path would make every resume a spec change, and a spec change **replaces
  the container**. That is the same reasoning the commissioned credential's columns carry, and it is
  why `WorkspacePostures` is a lookup by row id rather than a parameter threaded through
  `ContainerRuntime`.
- **It is decided once, in the request that created the workspace.** There is no promote verb and
  there must not be one: the point of the posture is that the socket belongs to the few workspaces
  somebody deliberately created for it, and a workspace that has been running for a week cannot
  acquire it. `recordWorkspace` is the only writer, private, with both its callers in
  `WorkspaceService`.
- **Every absence falls to false.** No port wired, no row, a read that threw — all of them mean *no
  socket*. That asymmetry with the credential lookup beside it is deliberate and is asserted:
  a credential lookup that stumbles costs a container something it was meant to have, while a
  posture lookup that stumbles must never **give** it something it was not.
- **Nothing else about the container changes.** Same image, same user, same limits, same mounts,
  same environment — `WorkspaceContainersTest` makes that claim as the admin spec with the socket
  taken back out, which fails if any other field moved. A posture that quietly relaxed the sandbox
  as well would be a privilege nobody asked for riding along with the one somebody did.
- **The socket is usable despite the host uid because qits-containers joins the socket's own
  group** beside the bind (`--group-add`, read off the socket by the orchestrator — its README's
  "The docker socket" section). A workspace-side group would be a privilege assembled here rather
  than granted there, and this service does not get to name a group. The client that talks to it is
  the workspace image's business: `images/qits-oci-workspace` carries the docker CLI, because a
  socket with nothing to speak to it is a bind and not a capability.
- **The read model says so.** `WorkspaceDto.admin` rides the listing and the create response,
  because a client that cannot see which workspaces are privileged cannot say so, and "which ones
  hold the socket" is the question the whole posture exists to keep answerable.

Who may ask is `WorkspaceController`'s standing `@RolesAllowed("qits:admin")`: creating **any**
workspace already requires the platform admin role, and the socket is granted per workspace rather
than per caller. A second role invented here would be a vocabulary qits-idp does not issue.

## Tests

- **App-level config lives in `service/src/main/resources/application.properties`, and the tests
  inherit it.** That file is on the test classpath and Quarkus merges it, so
  `service/src/test/resources/application.properties` holds test-only *overrides* (the test port,
  `flyway…clean-at-start`, the on-disk trees under `target/`) and nothing else. Never
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
  hold: the shipped datasource being the bare `QITS_RESOURCE_DB_*` contract with no `${user.home}`
  anywhere behind it — the two spellings of "config the binary cannot boot on" this repo paid for,
  `AUTO_SERVER` and a home-rooted file path — every nested record of `QitsConfig` and of
  `CaptureResource`'s payloads present in its `@RegisterForReflection(targets)`, and
  `CaptureCorsRoute` reading an application-owned config key. None of them proves a binary works —
  they prevent the silent re-introduction of what booting one already caught.
- **The suites run on a real postgres they spawn themselves.** Zonky resolves the binaries as
  ordinary Maven artifacts and `EmbeddedPg` starts one child process per surefire JVM — never
  Testcontainers, never a dev service, so the clone-alone no-docker rule survives the move off H2.
  Its port is chosen at run time, which is why the url/username/password are not in any properties
  file: `EmbeddedPgConfigSource` supplies them at ordinal 500, registered through
  `META-INF/services`. Both classes are **copied per module** (`domain`'s `…workspaces.testdb`,
  `service`'s `…workspaces.wiring.testdb`), because sharing them would mean a test-jar dependency
  between two modules that deliberately have none — the same reason the `Fake*` doubles are
  duplicated. **Every (module, datasource) pair names a distinct database** so two suites on one host
  cannot mean the same one.
- **The event bus is dark in `%dev` and `%test`, but its datasource is not.**
  `qits.eventstream.enabled=false` stops publishing and sweeping; Quarkus still opens the connection
  and runs Flyway at boot, so `service`'s `EmbeddedPgConfigSource` supplies the `eventstream`
  datasource's triple beside the `workspaces` one. Without it every run would try to open the
  database a deployment injects, and fail on an unresolvable expression. A test that wants the bus
  real turns it back on for itself against a stub, never against a live qits-events.
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
- `Port already bound: 8081` is settled rather than flaky now: `service`'s test properties set
  `quarkus.http.test-port=0`, so the OS picks and RestAssured is told. It was two failures wearing
  one message — `@QuarkusTest` restarts racing each other for the default test port
  (`migration-plan.md` §9 item 14), and 8081 being where the platform's own npm registry answers on
  the machine this repo is most likely built on. Keep the line; it is a test-only value and belongs
  in that file.
- `TestOrigin.create(dataDir)` builds a real bare origin (master + a diverging feature branch) and
  returns a repo id; pair it with `FakeRepositoryLookup.register`. Where those origins go is
  **`qits.test.origins-dir`, a key no deployment has** — the suite needs somewhere to put a fixture
  git host, and nothing in `src/main` reads it. It is deliberately a **different** directory from
  `qits.workspaces.data-dir`: the service's own tree must not be the tree it fetches from, or a
  suite would prove nothing about the separation. The properties files also set
  `qits.workspace.git.mirror-freshness-ms=0`, because a fixture that changes between two assertions
  must never be served from a window.
- `TestOrigin.recordPushOptions` installs a real `pre-receive` hook on a fixture origin that logs
  each ref with the push options it arrived under, and `pushOptionsFor` reads one back. It is the
  only way to see a `--push-option` from outside the pushing process, and it is what makes "the
  trunk push carries `qits.no-ci` exactly when there is a deploy branch" an assertion about the
  shipped argv. Install it *after* the fixture commits: it records every push from then on, and the
  reader takes the first line for a ref.
- `TestGit.exec` is how a test runs git in one of those fixture directories. It is a thin static
  helper over `gitmirror`'s `GitCli`, and it replaced `GitExecutor` — a production CDI bean whose
  only remaining callers were tests. `GitCli`'s own properties (the per-line tap, the unterminated
  final line, `GIT_TERMINAL_PROMPT=0`) are asserted in `gitmirror`'s `GitCliTest`, offline and with
  no augmentation, rather than through a `@QuarkusTest` on a bean that only delegated.
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
- **`FakeCredentialCommissioner` starts UNWIRED, and must stay that way for everyone else.** A bean
  in `src/test` is a bean for every `@QuarkusTest` in its module, so a double that commissioned by
  default would put credential environment into every container the suite launches and quietly
  change what dozens of unrelated tests are about. `wire()` turns it on, `reset()` turns it off, and
  a test class that wires it resets in `@AfterEach` — a class that forgets leaks its issuer into
  whatever runs next.
- Integration tests needing real docker, a built `qits/workspace` image and the daemon binary **are**
  in this repo — `Daemon*IT`, tagged `extended`, `DaemonApiGateIT` the largest of them. They
  self-skip (`assumeTrue`) when docker or the image is absent, and `skipITs=true` is the default
  anyway, so a clone-alone `mvn verify` never reaches them. Run them with
  `./mvnw verify -DskipITs=false`. Keep both properties: the default keeps `mvn verify` runnable
  anywhere, and the self-skip keeps a deliberate `-DskipITs=false` from failing on a machine that
  simply has no image.
  - Their image name comes from `-Dqits.workspace.image=`, falling back to
    `localhost:8081/qits/workspace:latest`. The check behind that fallback is `docker image
    inspect`, which **never pulls** — deliberately, and unlike a real launch, where qits-containers
    pulls the pin (see "The workspace image, and who pulls it"). An IT that pulled 3.4 GB to decide
    whether to skip would not be a self-skip.
    Nothing publishes a `latest` tag, so the fallback normally means "skip", and passing a real
    reference is the way to run them:
    `./mvnw verify -DskipITs=false -Dqits.workspace.image=localhost:8081/qits/workspace:<calver>`.
    The fallback is deliberately not a copy of the pin; the release train moves the pin and would
    not move five test literals. It used to be the bare `qits/workspace:latest`, which was a
    hand-built local tag on one machine — the whole disease the pin exists to end, and a spelling
    that must not come back anywhere as a default.
