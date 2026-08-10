-- The platform's generic causation column (qits-eventstream's CausedRow): the id of the event a row
-- was written because of, stamped from the ambient CausationScope at persist. Two tables take it —
-- the unit of work and its timeline — and the other four entities decline in one reviewable line
-- each, with the reason in their javadoc (service_event and workspace_bootstrap_run are written on
-- threads no scope stands on, workspace_prompt_draft is written by a native upsert no @PrePersist
-- can see, workspace_prompt_attachment is the draft's payload).
--
-- Nullable, part of no constraint, and never a FOREIGN KEY: the event it names lives in
-- qits-events' store, the same reason every repository id in this schema is a bare string. No
-- backfill — the column starts recording here, and a row written before it existed genuinely has no
-- recorded cause.
--
-- APPENDED, not edited into V1. V1's header argues the point at length: the H2 lineage was dropped
-- rather than continued because no postgres database ever ran it, and from there the ordinary rule
-- is back. This is the first file following it.
alter table workspace add column causation_id uuid;
alter table workspace_event add column causation_id uuid;
