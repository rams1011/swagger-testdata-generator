-- Test Data Studio schema
-- Used for testdata.storage.type = h2 and testdata.storage.type = database
-- Compatible with PostgreSQL 12+ and H2 2.x (MODE=PostgreSQL; DATABASE_TO_LOWER=TRUE)
-- Keep in sync with db/migration/V1__studio.sql (Flyway baseline).

CREATE TABLE IF NOT EXISTS td_tenant (
    tenant_id   VARCHAR(36)   NOT NULL,
    slug        VARCHAR(64)   NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    ready       BOOLEAN       NOT NULL DEFAULT FALSE,
    enabled_at  TIMESTAMP,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_tenant PRIMARY KEY (tenant_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_td_tenant_slug ON td_tenant (slug);

INSERT INTO td_tenant (tenant_id, slug, name, created_at)
SELECT '00000000-0000-0000-0000-000000000001', 'local', 'Local', CURRENT_TIMESTAMP
 WHERE NOT EXISTS (
     SELECT 1 FROM td_tenant WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
 );

INSERT INTO td_tenant (tenant_id, slug, name, created_at)
SELECT '00000000-0000-0000-0000-000000000002', 'saas-platform', 'SaaS', CURRENT_TIMESTAMP
 WHERE NOT EXISTS (
     SELECT 1 FROM td_tenant WHERE tenant_id = '00000000-0000-0000-0000-000000000002'
 );

ALTER TABLE td_tenant ADD COLUMN IF NOT EXISTS ready BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE td_tenant ADD COLUMN IF NOT EXISTS enabled_at TIMESTAMP;

UPDATE td_tenant
   SET ready = TRUE
 WHERE tenant_id IN (
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000002'
 );

CREATE TABLE IF NOT EXISTS td_account (
    account_id     VARCHAR(36)   NOT NULL,
    email          VARCHAR(320)  NOT NULL,
    password_hash  VARCHAR(255)  NOT NULL,
    display_name   VARCHAR(255)  NOT NULL,
    platform_role  VARCHAR(16)   NOT NULL DEFAULT 'USER',
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_account PRIMARY KEY (account_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_td_account_email ON td_account (email);

ALTER TABLE td_account ADD COLUMN IF NOT EXISTS platform_role VARCHAR(16) NOT NULL DEFAULT 'USER';

CREATE TABLE IF NOT EXISTS td_membership (
    tenant_id   VARCHAR(36)  NOT NULL,
    account_id  VARCHAR(36)  NOT NULL,
    role        VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_membership PRIMARY KEY (tenant_id, account_id),
    CONSTRAINT fk_td_membership_tenant FOREIGN KEY (tenant_id)
        REFERENCES td_tenant (tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_membership_account FOREIGN KEY (account_id)
        REFERENCES td_account (account_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_td_membership_account ON td_membership (account_id);

CREATE TABLE IF NOT EXISTS td_session (
    token_hash   VARCHAR(64)  NOT NULL,
    account_id   VARCHAR(36)  NOT NULL,
    tenant_id    VARCHAR(36)  NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_session PRIMARY KEY (token_hash),
    CONSTRAINT fk_td_session_account FOREIGN KEY (account_id)
        REFERENCES td_account (account_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_session_tenant FOREIGN KEY (tenant_id)
        REFERENCES td_tenant (tenant_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_td_session_account ON td_session (account_id);

CREATE TABLE IF NOT EXISTS td_spec (
    spec_id           VARCHAR(36)   NOT NULL,
    tenant_id         VARCHAR(36),
    name              VARCHAR(255),
    label             VARCHAR(255),
    title             VARCHAR(255)  NOT NULL,
    version           VARCHAR(64),
    description       TEXT,
    base_url          VARCHAR(1024),
    source_location   VARCHAR(2048),
    source_content    TEXT,
    operation_count   INTEGER       NOT NULL DEFAULT 0,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_spec PRIMARY KEY (spec_id)
);

ALTER TABLE td_spec ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(36);

UPDATE td_spec
   SET tenant_id = '00000000-0000-0000-0000-000000000001'
 WHERE tenant_id IS NULL OR TRIM(tenant_id) = '';

ALTER TABLE td_spec DROP CONSTRAINT IF EXISTS fk_td_spec_tenant;
ALTER TABLE td_spec ADD CONSTRAINT fk_td_spec_tenant FOREIGN KEY (tenant_id)
    REFERENCES td_tenant (tenant_id);

DROP INDEX IF EXISTS ux_td_spec_name;
CREATE UNIQUE INDEX IF NOT EXISTS ux_td_spec_tenant_name ON td_spec (tenant_id, name);
CREATE INDEX IF NOT EXISTS ix_td_spec_tenant ON td_spec (tenant_id);

CREATE TABLE IF NOT EXISTS td_test_case (
    test_case_id           VARCHAR(36)   NOT NULL,
    spec_id                VARCHAR(36)   NOT NULL,
    plan_id                VARCHAR(36),
    operation_key          VARCHAR(255)  NOT NULL,
    test_name              VARCHAR(255)  NOT NULL,
    http_method            VARCHAR(16)   NOT NULL,
    raw_path               VARCHAR(1024) NOT NULL,
    endpoint               VARCHAR(1024) NOT NULL,
    base_url               VARCHAR(1024),
    content_type           VARCHAR(64),
    request_body_json      TEXT,
    response_schema_json   TEXT,
    status_code            INTEGER       NOT NULL,
    response_type          VARCHAR(32),
    expected_response      TEXT,
    enabled                VARCHAR(8)    NOT NULL DEFAULT 'Y',
    test_group             VARCHAR(128),
    json_path_assertions   TEXT,
    extracts               TEXT,
    max_response_time_ms   BIGINT,
    environment_names      TEXT,
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_test_case PRIMARY KEY (test_case_id),
    CONSTRAINT fk_td_test_case_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS td_test_plan (
    plan_id      VARCHAR(36)   NOT NULL,
    spec_id      VARCHAR(36)   NOT NULL,
    name         VARCHAR(255)  NOT NULL,
    description  TEXT,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_test_plan PRIMARY KEY (plan_id),
    CONSTRAINT fk_td_test_plan_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
);

ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS plan_id VARCHAR(36);

ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS extracts TEXT;

ALTER TABLE td_spec ADD COLUMN IF NOT EXISTS name VARCHAR(255);

UPDATE td_spec
   SET name = COALESCE(NULLIF(TRIM(name), ''), NULLIF(TRIM(title), ''), spec_id)
 WHERE name IS NULL OR TRIM(name) = '';

ALTER TABLE td_spec ADD COLUMN IF NOT EXISTS label VARCHAR(255);

UPDATE td_spec
   SET label = 'General'
 WHERE label IS NULL OR TRIM(label) = '';

CREATE TABLE IF NOT EXISTS td_test_case_param (
    test_case_id  VARCHAR(36)  NOT NULL,
    param_in      VARCHAR(16)  NOT NULL,
    param_name    VARCHAR(255) NOT NULL,
    param_value   TEXT,
    CONSTRAINT pk_td_test_case_param PRIMARY KEY (test_case_id, param_in, param_name),
    CONSTRAINT fk_td_test_case_param_case FOREIGN KEY (test_case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE,
    CONSTRAINT ck_td_test_case_param_in CHECK (param_in IN ('path', 'query', 'header', 'cookie'))
);

ALTER TABLE td_test_case_param DROP CONSTRAINT IF EXISTS ck_td_test_case_param_in;
ALTER TABLE td_test_case_param ADD CONSTRAINT ck_td_test_case_param_in
    CHECK (param_in IN ('path', 'query', 'header', 'cookie'));

CREATE INDEX IF NOT EXISTS ix_td_test_case_spec
    ON td_test_case (spec_id);

CREATE INDEX IF NOT EXISTS ix_td_test_case_operation
    ON td_test_case (spec_id, operation_key);

CREATE INDEX IF NOT EXISTS ix_td_test_plan_spec
    ON td_test_plan (spec_id);

CREATE INDEX IF NOT EXISTS ix_td_test_case_plan
    ON td_test_case (plan_id);

CREATE INDEX IF NOT EXISTS ix_td_test_case_param_in
    ON td_test_case_param (param_in);

CREATE TABLE IF NOT EXISTS td_request_file (
    file_id       VARCHAR(36)   NOT NULL,
    test_case_id  VARCHAR(36)   NOT NULL,
    file_name     VARCHAR(512)  NOT NULL,
    content_type  VARCHAR(255),
    size_bytes    BIGINT        NOT NULL,
    content       BYTEA         NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_request_file PRIMARY KEY (file_id),
    CONSTRAINT fk_td_request_file_case FOREIGN KEY (test_case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE
);

ALTER TABLE td_request_file ADD COLUMN IF NOT EXISTS test_case_id VARCHAR(36);
DELETE FROM td_request_file WHERE test_case_id IS NULL;
ALTER TABLE td_request_file DROP CONSTRAINT IF EXISTS fk_td_request_file_spec;
DROP INDEX IF EXISTS ix_td_request_file_spec;
ALTER TABLE td_request_file DROP COLUMN IF EXISTS spec_id;
ALTER TABLE td_request_file ALTER COLUMN test_case_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_td_request_file_case
    ON td_request_file (test_case_id);

CREATE TABLE IF NOT EXISTS td_run (
    run_id          VARCHAR(36)  NOT NULL,
    spec_id         VARCHAR(36)  NOT NULL,
    plan_id         VARCHAR(36),
    environment_id  VARCHAR(36),
    run_trigger     VARCHAR(16)  NOT NULL,
    passed      INTEGER      NOT NULL DEFAULT 0,
    failed      INTEGER      NOT NULL DEFAULT 0,
    skipped     INTEGER      NOT NULL DEFAULT 0,
    started_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_run PRIMARY KEY (run_id),
    CONSTRAINT fk_td_run_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS td_run_result (
    result_id              VARCHAR(36)   NOT NULL,
    run_id                 VARCHAR(36)   NOT NULL,
    spec_id                VARCHAR(36)   NOT NULL,
    plan_id                VARCHAR(36),
    case_id                VARCHAR(36),
    operation_key          VARCHAR(255),
    test_name              VARCHAR(255),
    run_trigger            VARCHAR(16)   NOT NULL,
    outcome                VARCHAR(16)   NOT NULL,
    method                 VARCHAR(16),
    url                    VARCHAR(2048),
    expected_status        INTEGER,
    actual_status          INTEGER,
    status_matched         BOOLEAN,
    elapsed_ms             BIGINT,
    max_response_time_ms   BIGINT,
    timed_out              BOOLEAN,
    error                  TEXT,
    request_headers        TEXT,
    request_body           TEXT,
    request_content_type   VARCHAR(64),
    request_params         TEXT,
    response_headers       TEXT,
    response_body          TEXT,
    assertions_json        TEXT,
    download_path          VARCHAR(2048),
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_run_result PRIMARY KEY (result_id),
    CONSTRAINT fk_td_run_result_run FOREIGN KEY (run_id)
        REFERENCES td_run (run_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_run_result_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_run_result_case FOREIGN KEY (case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS ix_td_run_spec
    ON td_run (spec_id, started_at);

CREATE INDEX IF NOT EXISTS ix_td_run_result_run
    ON td_run_result (run_id);

CREATE INDEX IF NOT EXISTS ix_td_run_result_spec
    ON td_run_result (spec_id, created_at);

CREATE INDEX IF NOT EXISTS ix_td_run_result_case
    ON td_run_result (case_id);

CREATE TABLE IF NOT EXISTS td_environment (
    environment_id VARCHAR(36)   NOT NULL,
    spec_id        VARCHAR(36)   NOT NULL,
    name           VARCHAR(255)  NOT NULL,
    document_json  TEXT          NOT NULL,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_environment PRIMARY KEY (environment_id),
    CONSTRAINT fk_td_environment_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_td_environment_spec
    ON td_environment (spec_id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_td_environment_spec_name
    ON td_environment (spec_id, name);

ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS environment_names TEXT;
ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS created_by_account_id VARCHAR(36);
ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS created_by_label VARCHAR(320);
ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS updated_by_account_id VARCHAR(36);
ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS updated_by_label VARCHAR(320);
ALTER TABLE td_request_file DROP COLUMN IF EXISTS environment_name;

CREATE TABLE IF NOT EXISTS td_organization (
    org_id      VARCHAR(36)   NOT NULL,
    slug        VARCHAR(64)   NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_organization PRIMARY KEY (org_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_td_organization_slug ON td_organization (slug);

CREATE TABLE IF NOT EXISTS td_org_membership (
    org_id      VARCHAR(36)  NOT NULL,
    account_id  VARCHAR(36)  NOT NULL,
    role        VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_org_membership PRIMARY KEY (org_id, account_id),
    CONSTRAINT fk_td_org_membership_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_org_membership_account FOREIGN KEY (account_id)
        REFERENCES td_account (account_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_td_org_membership_account ON td_org_membership (account_id);

CREATE TABLE IF NOT EXISTS td_subscription (
    subscription_id     VARCHAR(36)  NOT NULL,
    org_id              VARCHAR(36)  NOT NULL,
    plan_code           VARCHAR(32)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    seat_limit          INTEGER      NOT NULL,
    workspace_limit     INTEGER      NOT NULL,
    spec_limit          INTEGER      NOT NULL,
    run_limit_month     INTEGER      NOT NULL,
    trial_ends_at       TIMESTAMP,
    current_period_end  TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_subscription PRIMARY KEY (subscription_id),
    CONSTRAINT fk_td_subscription_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_td_subscription_org ON td_subscription (org_id);

CREATE TABLE IF NOT EXISTS td_usage_month (
    org_id      VARCHAR(36) NOT NULL,
    year_month  VARCHAR(7)  NOT NULL,
    run_count   INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT pk_td_usage_month PRIMARY KEY (org_id, year_month),
    CONSTRAINT fk_td_usage_month_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE
);

ALTER TABLE td_tenant ADD COLUMN IF NOT EXISTS org_id VARCHAR(36);

INSERT INTO td_organization (org_id, slug, name, created_at)
SELECT t.tenant_id, t.slug, t.name, t.created_at
  FROM td_tenant t
 WHERE NOT EXISTS (SELECT 1 FROM td_organization o WHERE o.org_id = t.tenant_id);

UPDATE td_tenant
   SET org_id = tenant_id
 WHERE org_id IS NULL OR TRIM(org_id) = '';

INSERT INTO td_org_membership (org_id, account_id, role, created_at)
SELECT t.org_id, m.account_id, 'OWNER', m.created_at
  FROM td_membership m
  JOIN td_tenant t ON t.tenant_id = m.tenant_id
 WHERE m.role = 'OWNER'
   AND t.org_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM td_org_membership om
        WHERE om.org_id = t.org_id AND om.account_id = m.account_id
   );

INSERT INTO td_org_membership (org_id, account_id, role, created_at)
SELECT t.org_id, m.account_id, 'MEMBER', m.created_at
  FROM td_membership m
  JOIN td_tenant t ON t.tenant_id = m.tenant_id
 WHERE t.org_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM td_org_membership om
        WHERE om.org_id = t.org_id AND om.account_id = m.account_id
   );

INSERT INTO td_subscription (
    subscription_id, org_id, plan_code, status,
    seat_limit, workspace_limit, spec_limit, run_limit_month,
    created_at)
SELECT t.tenant_id, t.org_id,
       CASE
         WHEN t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 'enterprise'
         WHEN t.ready THEN 'team'
         ELSE 'trial'
       END,
       CASE
         WHEN t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 'active'
         WHEN t.ready THEN 'active'
         ELSE 'trial'
       END,
       CASE WHEN t.ready OR t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 10 ELSE 2 END,
       CASE WHEN t.ready OR t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 5 ELSE 1 END,
       CASE WHEN t.ready OR t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 50 ELSE 3 END,
       CASE WHEN t.ready OR t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 5000 ELSE 100 END,
       t.created_at
  FROM td_tenant t
 WHERE t.org_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM td_subscription s WHERE s.org_id = t.org_id);

ALTER TABLE td_tenant DROP CONSTRAINT IF EXISTS fk_td_tenant_org;
ALTER TABLE td_tenant ADD CONSTRAINT fk_td_tenant_org FOREIGN KEY (org_id)
    REFERENCES td_organization (org_id);

CREATE TABLE IF NOT EXISTS td_audit_event (
    event_id           VARCHAR(36)   NOT NULL,
    tenant_id          VARCHAR(36)   NOT NULL,
    spec_id            VARCHAR(36),
    resource_type      VARCHAR(32)   NOT NULL,
    resource_id        VARCHAR(36)   NOT NULL,
    action             VARCHAR(32)   NOT NULL,
    actor_account_id   VARCHAR(36),
    actor_label        VARCHAR(320),
    detail             VARCHAR(512),
    occurred_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_audit_event PRIMARY KEY (event_id)
);

CREATE INDEX IF NOT EXISTS ix_td_audit_tenant_spec_time
    ON td_audit_event (tenant_id, spec_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS ix_td_audit_tenant_resource_time
    ON td_audit_event (tenant_id, resource_type, resource_id, occurred_at DESC);
