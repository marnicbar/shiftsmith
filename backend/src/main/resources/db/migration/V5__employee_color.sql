-- Each person and position carries a stable colour index assigned at creation
-- (the frontend's theme.colorAt maps it to a distinct OKLCH swatch). Positions
-- already have a `color` column; give employees one too, and re-seed BOTH as
-- dense 0-based indices so the shared generator yields collision-free colours for
-- existing data, not just newly created rows.
ALTER TABLE employee ADD COLUMN color integer NOT NULL DEFAULT 0;

UPDATE employee e
SET color = sub.rn
FROM (SELECT id, (row_number() OVER (ORDER BY created_at, id) - 1) AS rn FROM employee) sub
WHERE e.id = sub.id;

UPDATE position p
SET color = sub.rn
FROM (SELECT id, (row_number() OVER (ORDER BY created_at, id) - 1) AS rn FROM position) sub
WHERE p.id = sub.id;
