-- The whole schema of the qits-workspaces bounded context, in one migration.
--
-- ONE V1 AND NO INHERITED LINEAGE. The H2 lineage (V1 squashed from the monorepo, V2's four host
-- tables, V3's one-active-workspace-per-branch rule, V4's service_event.workspace_row_id) is deleted
-- rather than continued, and one fact allowed it: the move onto postgres is an unwrap and a
-- re-bootstrap, not a data migration. No postgres database anywhere ever ran those four files, so no
-- `V5__move_to_postgres.sql` had a reader, and the H2 file they built is not a prefix of this one.
-- They are history in this repository's log. FROM HERE ON THE ORDINARY RULE IS BACK: keep appending,
-- never renumber, and never edit an applied migration's body — Flyway checksums it.
--
-- The shape below is those four translated and merged. What the translation changed, and nothing
-- else did:
--
--   * `clob` -> `text` and `blob` -> `bytea`, which is also the one ENTITY mapping the move had to
--     change. On H2 a `@Lob String` was a clob and the two agreed; on postgres `@Lob` means a LARGE
--     OBJECT — Hibernate binds an oid and the insert fails against a text/bytea column. The entities
--     say `columnDefinition` instead. Unbounded either way, which is what these columns need.
--   * V3's `active_branch` GENERATED column is gone, replaced by the PARTIAL UNIQUE INDEX it was a
--     workaround for. V3's own header says so in as many words: the rule wants
--     `unique (repository_id, branch) where status = 'ACTIVE'`, and H2 2.4.240 made that a syntax
--     error, so the predicate had to be carried in a generated value that a unique index would then
--     ignore as NULL. Postgres has partial indexes, so the rule can finally be written as the rule.
--     The semantics are identical, NULLs included: a row with no branch is exempt under both forms.
--     The column was never mapped on the Workspace entity, so nothing in Java notices it going.
--   * V2's four tables and V4's column are folded in where they belong, with NO BACKFILL, because
--     every database reaching this file is empty.
--
-- WHAT THE TRANSLATION DELIBERATELY KEPT, since a sibling component reads the other way:
--   * THE CHECK CONSTRAINTS ON THE ENUM COLUMNS SURVIVE. qits-platform-deployments dropped its own
--     because H2 2.4.240 tied a compiled IN-set to the session that made it and failed a valid
--     insert with 23514 — a defect, not a design. This lineage never met it and postgres has no such
--     behaviour, so the constraints stay what they always were here: a structural statement about
--     closed sets the Java enums already own.
--   * THE SEQUENCES STAY SEQUENCES, not identity columns. `Workspace.id` and `WorkspaceEvent.id` are
--     Hibernate-allocated with allocationSize 50 — the pooled optimizer is the point, one round trip
--     per fifty inserts — and an identity column would move allocation into the INSERT and give that
--     up for nothing. Postgres reads `create sequence` natively; there was no H2-ism to translate.
--     Unquoted here and unquoted in the entity mapping, so both fold to the same lowercase name.
--
-- Two properties of this context that outlive any storage engine, restated because the whole schema
-- is being rewritten and they are what a reader needs first:
--
--   * repository_id is a plain varchar with NO foreign key. A repository lives in another context
--     and another database; a repository deleted over there simply leaves its workspaces behind as
--     dangling history. RepositoryLookup is how the owning application is consulted.
--   * workspace rows are SOFT-deleted (status INTEGRATED/ABANDONED). Rows that other contexts hang
--     off a workspace therefore never see a cascade fire, which is why WorkspaceResolved is
--     published on resolution instead.

-- --- the workspace and its timeline ---------------------------------------------------------------

create sequence workspace_seq start with 1 increment by 50;
create sequence workspace_event_seq start with 1 increment by 50;

create table workspace (
  id             bigint       not null,
  workspace_id   varchar(255) not null,
  repository_id  varchar(255) not null,
  parent_id      varchar(255),
  -- The branch the workspace owns. Stored rather than derived: the checkout lives inside the
  -- workspace's container, so there is no host path to read `git branch --show-current` from.
  -- Nullable, because a row can predate the column (and, under the index below, is then exempt).
  branch         varchar(255),
  status         varchar(255) default 'ACTIVE'  not null
                 check (status in ('ACTIVE','INTEGRATED','ABANDONED')),
  -- Container state, separate from the lifecycle `status`: losing a container does not resolve a
  -- workspace, it just becomes STOPPED and is re-provisioned on demand. RUNNING is normally
  -- recomputed live from the container listing; the column carries the other values across restarts.
  runtime_status varchar(32)  default 'STOPPED' not null
                 check (runtime_status in ('RUNNING','STOPPED','PROVISIONING','FAILED')),
  runtime_error  varchar(2000),
  preamble       text,
  result         text,
  created_at     timestamp(6) with time zone,
  resolved_at    timestamp(6) with time zone,
  primary key (id)
);

-- No unique on (repository_id, workspace_id): resolved rows accumulate and a workspace id is
-- reusable once resolved. "At most one ACTIVE per id" is a service-layer invariant; this index
-- serves the ACTIVE finders that enforce it.
create index ix_workspace_active on workspace (repository_id, workspace_id, status);

-- AT MOST ONE ACTIVE WORKSPACE PER (repository_id, branch), and this is the rule as written rather
-- than as worked around.
--
-- A workspace IS a branch ref plus a container that clones it, so the branch — not the workspace id
-- — is the resource a workspace claims. Two ACTIVE workspaces on one branch means two independent
-- checkouts committing and auto-pushing to the same ref: interleaved history, non-fast-forward
-- rejections, and work that looks committed locally but never lands. The host-side gates that would
-- normally catch trouble (isWorkspaceClean, isFullyPushed) are evaluated per workspace, so each one
-- independently believes it is fine.
--
-- It is structural rather than a service check because the service guarded the wrong field once
-- already: `workspace_id` is a label that merely *defaults* to the branch, while `branch` is an
-- independently settable request field, so two distinct ids could name one branch and the invariant
-- survived only on git refusing to create a ref that already exists — which the `adoptExisting`
-- request flag skips.
--
-- Resolving a workspace frees its branch (the row leaves the predicate), and re-activating a
-- resolved row onto a taken branch is rejected. Rows with a NULL branch are exempt: postgres treats
-- NULLs as distinct in a unique index, exactly as the generated-column form relied on.
--
-- IF THIS MIGRATION FAILS HERE it found real duplicates rather than a mistake in itself:
--
--     select repository_id, branch, count(*) from workspace
--      where status = 'ACTIVE' and branch is not null
--      group by repository_id, branch having count(*) > 1;
--
-- Resolve them deliberately — INTEGRATE or ABANDON the workspace whose work already landed — and
-- re-run. This file does not choose for you: picking a loser means discarding a branch someone may
-- still be working in, which is an operator's call. (On a fresh database it cannot fire at all.)
--
-- Duplicate `workspace_id` values ACROSS repositories are near-certain — every repository tends to
-- have a workspace called `main` — and stay perfectly legal: this constrains the branch, which is
-- scoped per repository.
create unique index uq_workspace_active_branch
  on workspace (repository_id, branch)
  where status = 'ACTIVE';

create table workspace_event (
  id              bigint       not null,
  workspace_id_fk bigint       not null,
  type            varchar(255) not null
                  check (type in ('CREATED','MERGED','UPDATED_FROM_PARENT','INTEGRATED','ABANDONED')),
  branch          varchar(255),
  parent          varchar(255),
  target          varchar(255),
  commit_hash     varchar(255),
  note            varchar(2000),
  at              timestamp(6) with time zone not null,
  primary key (id),
  -- Intra-context, so this FK is real: the timeline has no meaning without its workspace.
  constraint fk_workspace_event_workspace
    foreign key (workspace_id_fk) references workspace (id) on delete cascade
);

create index ix_workspace_event_workspace on workspace_event (workspace_id_fk, at);

-- --- the host side of services, bootstrap and prompt composition -----------------------------------

-- Durable classified service events: observer findings and supervisor transitions survive the JVM.
-- Snapshot columns throughout — command_id is a plain column, not an FK, so deleting a command keeps
-- its events inspectable, and it names a row in the *command* context's database in any case. The
-- anchor columns locate the excerpt in its source: command_log_line sequences for source='output',
-- 1-based line numbers since source_epoch for a tailed file (whose lines are deliberately not copied
-- into the DB).
--
-- workspace_id is the workspace's *string* label, not the workspace table's surrogate key, so an
-- event outlives the row and there is no FK: this feed is diagnostic history.
--
-- workspace_row_id is the surrogate key beside it, and it is not a relation either. The label is
-- branch-derived and RECYCLABLE, so the label alone cannot say which workspace an event belonged to
-- — the SPA's recycled-label guard (service-events-feed.ts) filters on the row id for exactly that
-- reason, and it disowned a workspace's own events while this column did not exist. Nullable:
-- "provenance unknown" is a real value here.
create table service_event (
  id               varchar(255) not null,
  repo_id          varchar(255) not null,
  workspace_id     varchar(255) not null,
  workspace_row_id bigint,
  service_id       varchar(255) not null,
  service_name     varchar(255) not null,
  kind             varchar(255) not null
                   check (kind in ('STATUS_CHANGED','ERROR_DETECTED')),
  severity         varchar(255) check (severity in ('INFO','WARNING','ERROR')),
  status           varchar(255)
                   check (status in ('STARTING','READY','DEGRADED','RESTARTING','CRASHED','STOPPED')),
  summary          varchar(2000),
  log_excerpt      text,
  command_id       varchar(255),
  source           varchar(1024),
  anchor_from      bigint,
  anchor_to        bigint,
  source_epoch     timestamp(6) with time zone,
  at               timestamp(6) with time zone not null,
  primary key (id)
);

create index ix_service_event_workspace on service_event (repo_id, workspace_id, at desc);

-- One row per (workspace row, bootstrap command), overwritten on every run. The workspace FK carries
-- `on delete cascade` like every other workspace-child FK here; workspace rows are soft-deleted, so
-- it only ever fires on a real hard delete.
--
-- Deliberately absent, and it was absent in the H2 lineage too: bootstrap_command and
-- bootstrap_command_env. The BootstrapCommand entity exists nowhere — .config/qits/repository.yml is
-- the single source of truth — so bootstrap_command_id is a snapshot of a config-declared id, never
-- a foreign key.
create table workspace_bootstrap_run (
  id                   varchar(255) not null,
  workspace_id_fk      bigint       not null,
  bootstrap_command_id varchar(255) not null,
  command_name         varchar(255) not null,
  outcome              varchar(255) not null
                       check (outcome in ('SKIPPED','SUCCEEDED','FAILED')),
  command_id           varchar(255),
  exit_code            integer,
  ran_at               timestamp(6) with time zone not null,
  primary key (id),
  constraint uq_workspace_bootstrap_run unique (workspace_id_fk, bootstrap_command_id),
  constraint fk_workspace_bootstrap_run_workspace
    foreign key (workspace_id_fk) references workspace (id) on delete cascade
);

-- The per-workspace prompt-composition draft. One row per workspace: the primary key IS the
-- workspace row's id (a shared PK/FK), since the draft is strictly 1:1 with a workspace. `content`
-- is the opaque composition JSON the UI owns; `serialized_prompt` is the launch-ready markdown the
-- server serves to the agent. `prompt_version` is a monotonic counter bumped on every
-- content-changing upsert, naming the exact draft state a launch handed over; the `last_run_*`
-- columns record which version went to which agent run, so re-opening the tab does not re-deliver an
-- already-delivered prompt. last_run_command_id names a row in the command context's database and is
-- therefore a plain column.
--
-- The shared PK is also why WorkspacePromptDraftRepository upserts in ONE statement rather than
-- reading first — `insert ... on conflict (workspace_id_fk) do update`, which is where the H2
-- `merge into ... key (...)` went.
--
-- The cascade is defensive: the workspace row is soft-deleted, and the service deletes the draft
-- explicitly on discard.
create table workspace_prompt_draft (
  workspace_id_fk         bigint       not null,
  content                 text         not null,
  serialized_prompt       text,
  prompt_version          bigint       not null default 0,
  last_run_at             timestamp(6),
  last_run_prompt_version bigint,
  last_run_command_id     varchar(255),
  updated_at              timestamp(6) not null,
  primary key (workspace_id_fk),
  constraint fk_workspace_prompt_draft_workspace
    foreign key (workspace_id_fk) references workspace (id) on delete cascade
);

-- Image bytes attached to a workspace's prompt draft, as their own rows (n:1 with the workspace)
-- rather than base64 inside the draft's `content` blob: keeps that blob small and lets the server
-- enforce a per-image cap plus a PNG/JPEG magic-byte sniff at upload. Same soft-delete caveat as the
-- draft — the service deletes these explicitly on discard/abandon.
create table workspace_prompt_attachment (
  id              varchar(255) not null,
  workspace_id_fk bigint       not null,
  mime_type       varchar(255) not null,
  label           varchar(255) not null,
  source          varchar(255) not null,
  bytes           bytea        not null,
  created_at      timestamp(6) not null,
  primary key (id),
  constraint fk_workspace_prompt_attachment_workspace
    foreign key (workspace_id_fk) references workspace (id) on delete cascade
);

create index ix_workspace_prompt_attachment_workspace
  on workspace_prompt_attachment (workspace_id_fk);
