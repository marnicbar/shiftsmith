-- Phase 1 of issue #47: normalized, time-indexed schema.
--
-- Flyway baseline. Column/table shapes mirror exactly what the JPA entities expect
-- so Hibernate's `validate` (see application.properties) passes; the only additions
-- invisible to validation are ON DELETE CASCADE on the child FKs (so the full-document
-- rewrite in ProblemStore can wipe parents and let the DB prune children) and the
-- interval-query indexes. Every statement is idempotent (IF NOT EXISTS) so this runs
-- cleanly on a fresh database and on an existing one that Flyway baselines on first
-- migrate (the legacy/auth tables below already exist there and are left untouched).
--
-- `position` is a reserved word, so the table is quoted — matching Hibernate, which
-- quotes it too.

-- --- Legacy & auth tables (pre-existing; created here only on a fresh DB) -----
-- Previously created by Hibernate auto-DDL; we keep owning them so a fresh database
-- is fully provisioned by Flyway and so the legacy `problem` blob stays readable for
-- the one-time backfill (the table is dropped in a later phase). Column names match
-- Hibernate's defaults verbatim (e.g. `passwordhash`, `mustchangepassword`).
CREATE TABLE IF NOT EXISTS problem (
    id        bigint PRIMARY KEY,
    document  jsonb,
    updatedat timestamp(6) with time zone
);

CREATE TABLE IF NOT EXISTS app_user (
    id                 bigint PRIMARY KEY,
    mustchangepassword boolean,
    passwordhash       varchar(255) NOT NULL,
    username           varchar(255) NOT NULL UNIQUE
);
CREATE SEQUENCE IF NOT EXISTS app_user_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS auth_config (
    id     bigint PRIMARY KEY,
    secret varchar(512) NOT NULL
);

-- --- Catalogue & settings -----------------------------------------------------
CREATE TABLE IF NOT EXISTS settings (
    id            bigint PRIMARY KEY,
    created_at    timestamp(6) with time zone NOT NULL,
    updated_at    timestamp(6) with time zone NOT NULL,
    version       bigint  NOT NULL,
    horizon_count integer NOT NULL,
    horizon_unit  varchar(16) NOT NULL
);

CREATE TABLE IF NOT EXISTS skill (
    name       varchar(255) PRIMARY KEY,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version    bigint  NOT NULL,
    ordinal    integer NOT NULL
);

-- --- People & availability ----------------------------------------------------
CREATE TABLE IF NOT EXISTS employee (
    id         varchar(255) PRIMARY KEY,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version    bigint  NOT NULL,
    contract   integer NOT NULL,
    first_name varchar(255),
    last_name  varchar(255),
    role       varchar(255)
);

CREATE TABLE IF NOT EXISTS employee_skill (
    employee_id varchar(255) NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    skill       varchar(255)
);

CREATE TABLE IF NOT EXISTS availability_block (
    id          varchar(255) PRIMARY KEY,
    created_at  timestamp(6) with time zone NOT NULL,
    updated_at  timestamp(6) with time zone NOT NULL,
    version     bigint  NOT NULL,
    all_day     boolean NOT NULL,
    anchor_date date,
    days        integer[],
    employee_id varchar(255) NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    end_date    date,
    end_min     integer NOT NULL,
    repeat      varchar(16) NOT NULL,
    start_min   integer NOT NULL,
    type        varchar(16) NOT NULL,
    until_date  date
);
-- Interval query: blocks of an employee whose anchor lands in/near a range.
CREATE INDEX IF NOT EXISTS idx_availability_block_employee_anchor
    ON availability_block (employee_id, anchor_date);

CREATE TABLE IF NOT EXISTS availability_block_exception (
    block_id       varchar(255) NOT NULL REFERENCES availability_block(id) ON DELETE CASCADE,
    exception_date date
);

-- --- Working-time rules (time-varying) ---------------------------------------
-- employee_id NULL = global rule (replaces Settings.globalRules).
CREATE TABLE IF NOT EXISTS work_rule (
    id          varchar(255) PRIMARY KEY,
    created_at  timestamp(6) with time zone NOT NULL,
    updated_at  timestamp(6) with time zone NOT NULL,
    version     bigint  NOT NULL,
    employee_id varchar(255) REFERENCES employee(id) ON DELETE CASCADE,
    metric      varchar(16) NOT NULL,
    op          varchar(16) NOT NULL,
    value       integer NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_work_rule_employee ON work_rule (employee_id);

CREATE TABLE IF NOT EXISTS work_rule_change (
    rule_id        varchar(255) NOT NULL REFERENCES work_rule(id) ON DELETE CASCADE,
    ordinal        integer NOT NULL,
    change_id      varchar(255),
    effective_date date,
    kind           varchar(16),
    metric         varchar(16),
    op             varchar(16),
    value          integer NOT NULL,
    PRIMARY KEY (rule_id, ordinal),
    CONSTRAINT work_rule_change_ordinal_check CHECK (ordinal >= 0)
);

-- --- Positions & shift templates ---------------------------------------------
CREATE TABLE IF NOT EXISTS "position" (
    id         varchar(255) PRIMARY KEY,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version    bigint  NOT NULL,
    color      integer NOT NULL,
    grp        varchar(255),                     -- Position.group ("group" is reserved)
    name       varchar(255)
);

CREATE TABLE IF NOT EXISTS position_skill (
    position_id varchar(255) NOT NULL REFERENCES "position"(id) ON DELETE CASCADE,
    skill       varchar(255)
);

CREATE TABLE IF NOT EXISTS shift_template (
    id          varchar(255) PRIMARY KEY,
    created_at  timestamp(6) with time zone NOT NULL,
    updated_at  timestamp(6) with time zone NOT NULL,
    version     bigint  NOT NULL,
    anchor_date date,
    days        integer[],
    end_min     integer NOT NULL,
    headcount   integer NOT NULL,
    name        varchar(255),
    position_id varchar(255) NOT NULL REFERENCES "position"(id) ON DELETE CASCADE,
    repeat      varchar(16) NOT NULL,
    start_min   integer NOT NULL,
    until_date  date
);
CREATE INDEX IF NOT EXISTS idx_shift_template_position ON shift_template (position_id);
CREATE INDEX IF NOT EXISTS idx_shift_template_anchor ON shift_template (anchor_date);

CREATE TABLE IF NOT EXISTS shift_template_skill (
    template_id varchar(255) NOT NULL REFERENCES shift_template(id) ON DELETE CASCADE,
    skill       varchar(255)
);

CREATE TABLE IF NOT EXISTS shift_template_exception (
    template_id    varchar(255) NOT NULL REFERENCES shift_template(id) ON DELETE CASCADE,
    exception_date date
);

-- Ordered preferred employees. employee_id is intentionally not FK-constrained in
-- this phase: the document model tolerates references to people who may not (yet)
-- exist, and round-tripping must preserve them verbatim.
CREATE TABLE IF NOT EXISTS shift_template_preferred (
    template_id varchar(255) NOT NULL REFERENCES shift_template(id) ON DELETE CASCADE,
    ordinal     integer NOT NULL,
    employee_id varchar(255),
    PRIMARY KEY (template_id, ordinal),
    CONSTRAINT shift_template_preferred_ordinal_check CHECK (ordinal >= 0)
);

-- --- Assignments (solver output + manual pins) -------------------------------
-- The core new table. In Phase 1 it holds only manual pins migrated from the legacy
-- `overrides` map (pinned = true, source = 'manual'); Phase 2 makes the solver upsert
-- its solution here as durable, queryable history. employee_id is SET NULL on delete
-- so a person's removal leaves the (now unstaffed) slot rather than vanishing it.
CREATE TABLE IF NOT EXISTS assignment (
    id              bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    created_at      timestamp(6) with time zone NOT NULL,
    updated_at      timestamp(6) with time zone NOT NULL,
    version         bigint  NOT NULL,
    template_id     varchar(255) NOT NULL REFERENCES shift_template(id) ON DELETE CASCADE,
    occurrence_date date    NOT NULL,
    slot_index      integer NOT NULL,
    start_ts        timestamp(6) without time zone NOT NULL,
    end_ts          timestamp(6) without time zone NOT NULL,
    employee_id     varchar(255) REFERENCES employee(id) ON DELETE SET NULL,
    pinned          boolean NOT NULL,
    source          varchar(16) NOT NULL,
    solved_at       timestamp(6) with time zone,
    CONSTRAINT uk_assignment_slot UNIQUE (template_id, occurrence_date, slot_index)
);
CREATE INDEX IF NOT EXISTS idx_assignment_occurrence ON assignment (occurrence_date);
CREATE INDEX IF NOT EXISTS idx_assignment_employee_occurrence ON assignment (employee_id, occurrence_date);
