-- Phase 7 of issue #47: the document model is gone — every write is granular and the
-- problem is rehydrated from the normalized rows. Drop the legacy single-row JSONB
-- `problem` blob, which was kept only as a one-time backfill source.
DROP TABLE IF EXISTS problem;
