-- The four tables the rest of this context's host side needs, in their post-V45 shape.
--
-- Squashed from the monorepo's V17 (daemon_event) + V24 (worktree_id -> workspace_id) + V45
-- (daemon_event -> service_event and its two id columns) for service_event; V35 for
-- workspace_bootstrap_run; V36 + V38 for workspace_prompt_draft; V37 for
-- workspace_prompt_attachment. Collapsed to the final names, not replayed.
--
-- This is V2, not a rewrite of V1: the repo's lineage already shipped, so `workspace` and
-- `workspace_event` stay exactly where they are. V1's header note that V35-V38 were "deliberately
-- NOT here" is superseded -- section 3.2 and the split contract both put workspace_bootstrap_run,
-- workspace_prompt_draft and workspace_prompt_attachment on this side. The daemon executes the
-- bootstrap chain and composes nothing; recording *that a run happened*, and holding the draft a
-- user is still typing, are host facts about a workspace row that lives in this database.
--
-- Deliberately absent: bootstrap_command and bootstrap_command_env (V35's definition tables). The
-- BootstrapCommand entity no longer exists anywhere -- Part 5 made .config/qits/repository.yml the
-- single source of truth -- so workspace_bootstrap_run.bootstrap_command_id is a snapshot of a
-- config-declared id, never a foreign key. That is also why nothing here reaches into the
-- repositories database.

-- Durable classified service events: observer findings and supervisor transitions survive the JVM
-- (they were a 500-entry in-memory ring before V17). Snapshot columns throughout -- command_id is a
-- plain column, not an FK, so deleting a command keeps its events inspectable, and it names a row
-- in the *command* context's database in any case. The anchor columns locate the excerpt in its
-- source: command_log_line sequences for source='output', 1-based line numbers since source_epoch
-- for a tailed file (whose lines are deliberately not copied into the DB).
--
-- workspace_id is the workspace's *string* id, not the workspace table's surrogate key, so an
-- event outlives the row and there is no FK: this feed is diagnostic history.
create table service_event (
  id           varchar(255) not null,
  repo_id      varchar(255) not null,
  workspace_id varchar(255) not null,
  service_id   varchar(255) not null,
  service_name varchar(255) not null,
  kind         varchar(255) not null
               check (kind in ('STATUS_CHANGED','ERROR_DETECTED')),
  severity     varchar(255) check (severity in ('INFO','WARNING','ERROR')),
  status       varchar(255)
               check (status in ('STARTING','READY','DEGRADED','RESTARTING','CRASHED','STOPPED')),
  summary      varchar(2000),
  log_excerpt  clob,
  command_id   varchar(255),
  source       varchar(1024),
  anchor_from  bigint,
  anchor_to    bigint,
  source_epoch timestamp(6) with time zone,
  at           timestamp(6) with time zone not null,
  primary key (id)
);

create index IX_service_event_workspace on service_event (repo_id, workspace_id, at desc);

-- One row per (workspace row, bootstrap command), overwritten on every run. The workspace FK
-- carries `on delete cascade` like every other workspace-child FK here; workspace rows are
-- soft-deleted, so it only ever fires on a real hard delete.
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
  constraint UQ_workspace_bootstrap_run unique (workspace_id_fk, bootstrap_command_id),
  constraint FK_workspace_bootstrap_run_workspace
    foreign key (workspace_id_fk) references workspace (id) on delete cascade
);

-- The per-workspace prompt-composition draft. One row per workspace: the primary key IS the
-- workspace row's id (a shared PK/FK), since the draft is strictly 1:1 with a workspace. `content`
-- is the opaque composition JSON the UI owns; `serialized_prompt` is the launch-ready markdown the
-- server serves to the agent. `prompt_version` is a monotonic counter bumped on every
-- content-changing upsert, naming the exact draft state a launch handed over; the `last_run_*`
-- columns record which version went to which agent run, so re-opening the tab does not re-deliver
-- an already-delivered prompt. last_run_command_id names a row in the command context's database
-- and is therefore a plain column.
--
-- The cascade is defensive: the workspace row is soft-deleted, and the service deletes the draft
-- explicitly on discard.
create table workspace_prompt_draft (
  workspace_id_fk         bigint       not null,
  content                 clob         not null,
  serialized_prompt       clob,
  prompt_version          bigint       not null default 0,
  last_run_at             timestamp(6),
  last_run_prompt_version bigint,
  last_run_command_id     varchar(255),
  updated_at              timestamp(6) not null,
  primary key (workspace_id_fk),
  constraint FK_workspace_prompt_draft_workspace
    foreign key (workspace_id_fk) references workspace (id) on delete cascade
);

-- Image bytes attached to a workspace's prompt draft, as their own rows (n:1 with the workspace)
-- rather than base64 inside the draft's `content` blob: keeps that blob small and lets the server
-- enforce a per-image cap plus a PNG/JPEG magic-byte sniff at upload. Same soft-delete caveat as
-- the draft -- the service deletes these explicitly on discard/abandon.
create table workspace_prompt_attachment (
  id              varchar(255) not null,
  workspace_id_fk bigint       not null,
  mime_type       varchar(255) not null,
  label           varchar(255) not null,
  source          varchar(255) not null,
  bytes           blob         not null,
  created_at      timestamp(6) not null,
  primary key (id),
  constraint FK_workspace_prompt_attachment_workspace
    foreign key (workspace_id_fk) references workspace (id) on delete cascade
);

create index IX_workspace_prompt_attachment_workspace
  on workspace_prompt_attachment (workspace_id_fk);
