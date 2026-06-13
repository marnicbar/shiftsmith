-- Phase 6 of issue #47: multi-user authorization. An `app_user` login gains a role
-- and an optional link to the person it represents, so an employee account can be
-- authorized to edit only its own calendar (availability + working-time rules).
--
-- The existing seeded admin row predates these columns; the DEFAULT 'admin' keeps it
-- a full-access account. New accounts set their role explicitly.

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS role varchar(16) NOT NULL DEFAULT 'admin';
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS employee_id varchar(255);

-- Link a login to a person; if the person is deleted the account is simply unlinked
-- (it can no longer edit any calendar until relinked). Guarded so a re-run is a no-op.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_app_user_employee'
    ) THEN
        ALTER TABLE app_user
            ADD CONSTRAINT fk_app_user_employee
            FOREIGN KEY (employee_id) REFERENCES employee(id) ON DELETE SET NULL;
    END IF;
END $$;
