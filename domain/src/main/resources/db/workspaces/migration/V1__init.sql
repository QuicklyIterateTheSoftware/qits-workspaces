-- Consolidated initial schema for the qits-workspaces bounded context.
--
-- Squashed from the monorepo's V1__init.sql + V10__worktree_history.sql + V20__worktree_branch.sql
-- + V21__worktree_runtime_status.sql + V24__rename_worktree_to_workspace.sql, collapsed to the
-- final post-rename names. The monorepo's V35 (workspace_bootstrap_run) and V36-V38 (prompt drafts
-- and attachments) are deliberately NOT here: bootstrap execution and prompt composition belong to
-- the workspace-daemon, not to this context.
--
-- Its OWN Flyway lineage on its OWN datasource (the artifacts/ci precedent), which is what makes a
-- standalone deployable possible. Two consequences are load-bearing:
--
--   * repository_id is a plain varchar with NO foreign key. A repository lives in another context,
--     possibly another database; a repository deleted over there simply leaves its workspaces
--     behind as dangling history. RepositoryLookup is how the owning application is consulted.
--   * workspace rows are SOFT-deleted (status INTEGRATED/ABANDONED). Rows that other contexts hang
--     off a workspace therefore never see a cascade fire, which is why WorkspaceResolved is
--     published on resolution instead.

create sequence workspace_SEQ start with 1 increment by 50;
create sequence workspace_event_SEQ start with 1 increment by 50;

create table workspace (
  id             bigint       not null,
  workspace_id   varchar(255) not null,
  repository_id  varchar(255) not null,
  parent_id      varchar(255),
  -- The branch the workspace owns. Stored rather than derived: the checkout lives inside the
  -- workspace's container, so there is no host path to read `git branch --show-current` from.
  -- Nullable, matching V20 — rows can predate the column.
  branch         varchar(255),
  status         varchar(255) default 'ACTIVE'  not null
                 check (status in ('ACTIVE','INTEGRATED','ABANDONED')),
  -- Container state, separate from the lifecycle `status`: losing a container does not resolve a
  -- workspace, it just becomes STOPPED and is re-provisioned on demand. RUNNING is normally
  -- recomputed live from the container listing; the column carries the other values across
  -- restarts. The check constraint is new here — V21 shipped none.
  runtime_status varchar(32)  default 'STOPPED' not null
                 check (runtime_status in ('RUNNING','STOPPED','PROVISIONING','FAILED')),
  runtime_error  varchar(2000),
  preamble       clob,
  result         clob,
  created_at     timestamp(6) with time zone,
  resolved_at    timestamp(6) with time zone,
  primary key (id)
);

-- No unique on (repository_id, workspace_id): V10 dropped V1's, because resolved rows accumulate
-- and a workspace id is reusable once resolved. "At most one ACTIVE per id" is a service-layer
-- invariant. This index serves the ACTIVE finders that enforce it.
create index IX_workspace_active on workspace (repository_id, workspace_id, status);

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
  constraint FK_workspace_event_workspace
    foreign key (workspace_id_fk) references workspace (id) on delete cascade
);

create index IX_workspace_event_workspace on workspace_event (workspace_id_fk, at);
