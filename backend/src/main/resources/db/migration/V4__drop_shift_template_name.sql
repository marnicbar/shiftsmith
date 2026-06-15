-- The shift name turned out to be redundant — a shift is fully identified by its
-- owning position (plus its time and recurrence), so the per-template name is gone
-- from the model and UI. Drop the now-unmapped column.
ALTER TABLE shift_template DROP COLUMN IF EXISTS name;
