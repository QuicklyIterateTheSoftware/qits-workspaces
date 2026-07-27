-- At most one ACTIVE workspace per (repository_id, branch).
--
-- A workspace IS a branch ref plus a container that clones it, so the branch — not the workspace id
-- — is the resource a workspace claims. Two ACTIVE workspaces on one branch means two independent
-- checkouts committing and auto-pushing to the same ref: interleaved history, non-fast-forward
-- rejections, and work that looks committed locally but never lands. The host-side gates that would
-- normally catch trouble (isWorkspaceClean, isFullyPushed) are evaluated per workspace, so each one
-- independently believes it is fine.
--
-- V1 left this to the service layer -- "at most one ACTIVE per id" -- and the service guarded the
-- wrong field: `workspace_id` is a label that merely *defaults* to the branch, while `branch` is an
-- independently settable request field. Two distinct ids could therefore name one branch, and the
-- invariant survived only incidentally, on git refusing to create a ref that already exists. The
-- `adoptExisting` request flag skips exactly that step. The rule is structural here so the next
-- caller cannot route around it, which is how the defect arose in the first place.
--
-- WHY A GENERATED COLUMN AND NOT A FILTERED INDEX. The rule wants
--
--     create unique index ... on workspace (repository_id, branch) where status = 'ACTIVE'
--
-- but H2 (2.4.240, the only target -- prod values are file-H2) has no partial indexes; the WHERE
-- clause is a syntax error. `active_branch` carries the filter in the value instead: it is the
-- branch for an ACTIVE row and NULL otherwise, and a unique index ignores NULLs, so resolved rows
-- drop out of the constraint exactly as the predicate would have dropped them. That NULL-tolerance
-- is the whole reason the original unique constraint on (repository_id, workspace_id) could not
-- survive: V10 dropped it because soft-deleted rows accumulate and collide with a live one. This
-- one does not have that problem.
--
-- The column is GENERATED ALWAYS, so it is derived on write and on every status change -- resolving
-- a workspace frees its branch, and re-activating a resolved row onto a taken branch is rejected.
-- It is deliberately NOT mapped on the Workspace entity: it is an index-support column, not state.
--
-- Rows that predate V20's `branch` column have branch NULL and are likewise exempt.
--
-- IF THIS MIGRATION FAILS, it found real duplicates rather than a mistake in itself. They are
-- unlikely (two workspaces on one branch push over each other fast enough to be noticed), but this
-- lists them:
--
--     select repository_id, branch, count(*) from workspace
--      where status = 'ACTIVE' and branch is not null
--      group by repository_id, branch having count(*) > 1;
--
-- Resolve the duplicates deliberately -- INTEGRATE or ABANDON the workspace whose work already
-- landed -- and re-run. This file does not choose for you: picking a loser means discarding a
-- branch someone may still be working in, which is an operator's call, not a schema change's.
--
-- Note that duplicate `workspace_id` values ACROSS repositories are near-certain (every repository
-- tends to have a workspace called `main`) and stay perfectly legal: this constrains the branch,
-- which is scoped per repository.

alter table workspace
  add column active_branch varchar(255)
  generated always as (case when status = 'ACTIVE' then branch end);

create unique index UQ_workspace_active_branch on workspace (repository_id, active_branch);
