-- Stage 2: the database enforces what only the application enforced before.
-- MySQL 8.4+ version of db/migration/V2__integrity.sql: same changes, MySQL syntax. V3 (Java) moves case environment names
-- into td_test_case_environment and drops td_test_case.environment_names.

-- ---------- Which environments a case belongs to, by ID ----------
-- No rows means the case is in every environment.
-- utf8mb4_bin matches V1. MySQL 8.4's default collation cannot reference those columns.
CREATE TABLE td_test_case_environment (
    test_case_id    VARCHAR(36)  NOT NULL,
    environment_id  VARCHAR(36)  NOT NULL,
    CONSTRAINT pk_td_test_case_environment PRIMARY KEY (test_case_id, environment_id),
    CONSTRAINT fk_td_tce_case FOREIGN KEY (test_case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_tce_environment FOREIGN KEY (environment_id)
        REFERENCES td_environment (environment_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_test_case_environment_env ON td_test_case_environment (environment_id);

-- ---------- Required references ----------
UPDATE td_spec
   SET tenant_id = '00000000-0000-0000-0000-000000000001'
 WHERE tenant_id IS NULL;
UPDATE td_spec
   SET name = COALESCE(NULLIF(TRIM(title), ''), spec_id)
 WHERE name IS NULL OR TRIM(name) = '';
UPDATE td_spec
   SET label = 'General'
 WHERE label IS NULL OR TRIM(label) = '';
ALTER TABLE td_spec MODIFY tenant_id VARCHAR(36) NOT NULL;
ALTER TABLE td_spec MODIFY name VARCHAR(255) NOT NULL;
ALTER TABLE td_spec MODIFY label VARCHAR(255) NOT NULL;

-- A case belongs to a plan of its own collection.
UPDATE td_test_case
   SET plan_id = (SELECT MIN(p.plan_id) FROM td_test_plan p WHERE p.spec_id = td_test_case.spec_id)
 WHERE plan_id IS NULL
    OR plan_id NOT IN (SELECT p.plan_id FROM td_test_plan p WHERE p.spec_id = td_test_case.spec_id);
DELETE FROM td_test_case WHERE plan_id IS NULL;
ALTER TABLE td_test_case MODIFY plan_id VARCHAR(36) NOT NULL;
ALTER TABLE td_test_case ADD CONSTRAINT fk_td_test_case_plan FOREIGN KEY (plan_id)
    REFERENCES td_test_plan (plan_id) ON DELETE CASCADE;

-- Run history outlives the plans and environments it ran against.
UPDATE td_run SET plan_id = NULL WHERE plan_id NOT IN (SELECT plan_id FROM td_test_plan);
UPDATE td_run SET environment_id = NULL WHERE environment_id NOT IN (SELECT environment_id FROM td_environment);
UPDATE td_run_result SET plan_id = NULL WHERE plan_id NOT IN (SELECT plan_id FROM td_test_plan);
ALTER TABLE td_run ADD CONSTRAINT fk_td_run_plan FOREIGN KEY (plan_id)
    REFERENCES td_test_plan (plan_id) ON DELETE SET NULL;
ALTER TABLE td_run ADD CONSTRAINT fk_td_run_environment FOREIGN KEY (environment_id)
    REFERENCES td_environment (environment_id) ON DELETE SET NULL;
ALTER TABLE td_run_result ADD CONSTRAINT fk_td_run_result_plan FOREIGN KEY (plan_id)
    REFERENCES td_test_plan (plan_id) ON DELETE SET NULL;

DELETE FROM td_global_variables WHERE tenant_id NOT IN (SELECT tenant_id FROM td_tenant);
ALTER TABLE td_global_variables ADD CONSTRAINT fk_td_global_variables_tenant FOREIGN KEY (tenant_id)
    REFERENCES td_tenant (tenant_id) ON DELETE CASCADE;

-- Not keyed, on purpose: td_private_target_allowlist (configuration can name an environment before it exists) and
-- td_audit_event (a log, written at startup too, that must never stop the app or vanish with what it describes).

-- ---------- Revision numbers for conflict checks ----------
-- Each save adds one, and a save that expects an older revision is refused. Timestamps stay for display.
ALTER TABLE td_spec ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE td_test_case ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE td_test_case_draft ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
-- The published revision the draft started from (was base_updated_at).
ALTER TABLE td_test_case_draft ADD COLUMN base_revision BIGINT;
UPDATE td_test_case_draft
   SET base_revision = (SELECT c.revision FROM td_test_case c WHERE c.test_case_id = td_test_case_draft.test_case_id);
ALTER TABLE td_test_case_draft DROP COLUMN base_updated_at;

-- Order of audit events recorded in the same millisecond.
ALTER TABLE td_audit_event ADD COLUMN event_seq BIGINT NOT NULL AUTO_INCREMENT UNIQUE;

-- ---------- Allowed values ----------
ALTER TABLE td_test_case ADD COLUMN enabled_flag BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE td_test_case SET enabled_flag = CASE WHEN UPPER(TRIM(enabled)) = 'N' THEN FALSE ELSE TRUE END;
ALTER TABLE td_test_case DROP COLUMN enabled;
ALTER TABLE td_test_case RENAME COLUMN enabled_flag TO enabled;

-- Written as CASE, not IN (...): H2 2.4 evaluates an IN list with the connection that created the check, and
-- fails every insert once that connection has closed. V1's parameter check used IN, so it is replaced here.
ALTER TABLE td_test_case_param DROP CONSTRAINT ck_td_test_case_param_in;
ALTER TABLE td_test_case_param ADD CONSTRAINT ck_td_test_case_param_in CHECK (
    CASE param_in WHEN 'path' THEN TRUE WHEN 'query' THEN TRUE WHEN 'header' THEN TRUE WHEN 'cookie' THEN TRUE
                  ELSE FALSE END);
ALTER TABLE td_test_case ADD CONSTRAINT ck_td_test_case_lifecycle CHECK (
    CASE lifecycle WHEN 'DRAFT' THEN TRUE WHEN 'READY' THEN TRUE ELSE FALSE END);
ALTER TABLE td_run ADD CONSTRAINT ck_td_run_trigger CHECK (
    CASE run_trigger WHEN 'try' THEN TRUE WHEN 'case' THEN TRUE WHEN 'plan' THEN TRUE ELSE FALSE END);
ALTER TABLE td_run_result ADD CONSTRAINT ck_td_run_result_trigger CHECK (
    CASE run_trigger WHEN 'try' THEN TRUE WHEN 'case' THEN TRUE WHEN 'plan' THEN TRUE ELSE FALSE END);
ALTER TABLE td_run_result ADD CONSTRAINT ck_td_run_result_outcome CHECK (
    CASE outcome WHEN 'passed' THEN TRUE WHEN 'failed' THEN TRUE WHEN 'skipped' THEN TRUE WHEN 'error' THEN TRUE
                 ELSE FALSE END);
ALTER TABLE td_membership ADD CONSTRAINT ck_td_membership_role CHECK (
    CASE role WHEN 'OWNER' THEN TRUE WHEN 'ADMIN' THEN TRUE WHEN 'MEMBER' THEN TRUE ELSE FALSE END);
ALTER TABLE td_org_membership ADD CONSTRAINT ck_td_org_membership_role CHECK (
    CASE role WHEN 'OWNER' THEN TRUE WHEN 'ADMIN' THEN TRUE WHEN 'MEMBER' THEN TRUE ELSE FALSE END);
