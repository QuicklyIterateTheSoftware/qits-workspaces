-- Drops every CHECK constraint V1/V2 put on enum-mirroring columns. Each one duplicates an
-- invariant the Java enums already enforce at the only write path, and under H2 2.4.240 (the only
-- target) the duplicate is a landmine: a check's compiled IN-set keeps a reference to the session
-- that compiled it, and once the pool retires that session every evaluation fails as 23514
-- "Check constraint invalid" with an EMPTY detail — h2database#4063/#4291.
--
-- Seen live on the V4-applying boot: two service_event inserts succeeded, then every insert on the
-- replacement session failed against CONSTRAINT_8 (the `kind` check) until a restart recompiled
-- the world. The constraint text and the data were never the problem — the recovered on-disk DB
-- accepts the identical insert, and a restart with zero schema or data change cleared it, which a
-- value violation cannot do. The enum (ServiceEventKind has exactly STATUS_CHANGED) could not have
-- produced a violating value in the first place.
--
-- Any future ALTER on a checked table re-arms the trap, so the fix is not to recreate the checks
-- but to remove the whole class: the enums are the domain boundary, and the database keeps no
-- second copy of it. NO NEW CHECK CONSTRAINTS on enum columns — this file is the precedent.
--
-- The names below are H2's auto-generated ones, verified against a scratch 2.4.240 that replayed
-- V1→V4 (CONSTRAINT_8 matching the live error is the cross-check); IF EXISTS guards any drift.

alter table workspace_event drop constraint if exists CONSTRAINT_3;
alter table service_event drop constraint if exists CONSTRAINT_8;
alter table service_event drop constraint if exists CONSTRAINT_88;
alter table service_event drop constraint if exists CONSTRAINT_88D;
alter table workspace_bootstrap_run drop constraint if exists CONSTRAINT_C;
alter table workspace drop constraint if exists CONSTRAINT_E;
alter table workspace drop constraint if exists CONSTRAINT_E8;
