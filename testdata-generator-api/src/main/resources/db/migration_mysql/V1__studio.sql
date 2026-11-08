-- Test Data Studio schema, MySQL 8.4+ (InnoDB, utf8mb4).
-- Same tables, columns, keys and indexes as db/migration/V1__studio.sql, with MySQL types: LONGTEXT for text
-- (MySQL TEXT stops at 64 KB), DATETIME(3) for times (millisecond precision, no time zone conversion), and
-- LONGBLOB for files. SchemaSnapshotTest and the integration test hold both to schema-snapshot.txt.
--
-- Fresh baseline (2026-11-07): replaces V1 to V16 written during development. Change the schema only with a
-- new V2, V3... migration in both folders; never edit an applied migration.
-- Default workspaces are seeded by R__default_workspaces.sql.

-- ---------- Organizations, workspaces, accounts ----------

CREATE TABLE td_organization (
    org_id      VARCHAR(36)   NOT NULL,
    slug        VARCHAR(64)   NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_organization PRIMARY KEY (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE UNIQUE INDEX ux_td_organization_slug ON td_organization (slug);

-- A workspace (historically called a tenant).
CREATE TABLE td_tenant (
    tenant_id   VARCHAR(36)   NOT NULL,
    slug        VARCHAR(64)   NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    ready       BOOLEAN       NOT NULL DEFAULT FALSE,
    enabled_at  DATETIME(3),
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    org_id      VARCHAR(36),
    CONSTRAINT pk_td_tenant PRIMARY KEY (tenant_id),
    CONSTRAINT fk_td_tenant_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE UNIQUE INDEX ux_td_tenant_slug ON td_tenant (slug);

CREATE TABLE td_account (
    account_id            VARCHAR(36)   NOT NULL,
    email                 VARCHAR(320)  NOT NULL,
    password_hash         VARCHAR(255)  NOT NULL,
    display_name          VARCHAR(255)  NOT NULL,
    platform_role         VARCHAR(16)   NOT NULL DEFAULT 'USER',
    created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    password_changed_at   DATETIME(3),
    must_change_password  BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_td_account PRIMARY KEY (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE UNIQUE INDEX ux_td_account_email ON td_account (email);

CREATE TABLE td_password_history (
    history_id     VARCHAR(36)   NOT NULL,
    account_id     VARCHAR(36)   NOT NULL,
    password_hash  VARCHAR(255)  NOT NULL,
    retired_at     DATETIME(3)     NOT NULL,
    CONSTRAINT pk_td_password_history PRIMARY KEY (history_id),
    CONSTRAINT fk_td_password_history_account FOREIGN KEY (account_id)
        REFERENCES td_account (account_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_password_history_account ON td_password_history (account_id, retired_at);

CREATE TABLE td_membership (
    tenant_id   VARCHAR(36)  NOT NULL,
    account_id  VARCHAR(36)  NOT NULL,
    role        VARCHAR(16)  NOT NULL,
    created_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_membership PRIMARY KEY (tenant_id, account_id),
    CONSTRAINT fk_td_membership_tenant FOREIGN KEY (tenant_id)
        REFERENCES td_tenant (tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_membership_account FOREIGN KEY (account_id)
        REFERENCES td_account (account_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_membership_account ON td_membership (account_id);

CREATE TABLE td_org_membership (
    org_id      VARCHAR(36)  NOT NULL,
    account_id  VARCHAR(36)  NOT NULL,
    role        VARCHAR(16)  NOT NULL,
    created_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_org_membership PRIMARY KEY (org_id, account_id),
    CONSTRAINT fk_td_org_membership_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_org_membership_account FOREIGN KEY (account_id)
        REFERENCES td_account (account_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_org_membership_account ON td_org_membership (account_id);

CREATE TABLE td_session (
    token_hash    VARCHAR(64)  NOT NULL,
    account_id    VARCHAR(36)  NOT NULL,
    tenant_id     VARCHAR(36)  NOT NULL,
    expires_at    DATETIME(3)    NOT NULL,
    created_at    DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_seen_at  DATETIME(3),
    CONSTRAINT pk_td_session PRIMARY KEY (token_hash),
    CONSTRAINT fk_td_session_account FOREIGN KEY (account_id)
        REFERENCES td_account (account_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_session_tenant FOREIGN KEY (tenant_id)
        REFERENCES td_tenant (tenant_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_session_account ON td_session (account_id);

CREATE TABLE td_login_guard (
    subject            VARCHAR(320) NOT NULL,
    attempt_count      INT      NOT NULL,
    window_started_at  DATETIME(3)    NOT NULL,
    locked_until       DATETIME(3),
    CONSTRAINT pk_td_login_guard PRIMARY KEY (subject)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE td_workspace_access_request (
    request_id   VARCHAR(36)    NOT NULL,
    tenant_id    VARCHAR(36)    NOT NULL,
    account_id   VARCHAR(36)    NOT NULL,
    message      VARCHAR(2000),
    status       VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    created_at   DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_workspace_access_request PRIMARY KEY (request_id),
    CONSTRAINT fk_td_war_tenant FOREIGN KEY (tenant_id)
        REFERENCES td_tenant (tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_war_account FOREIGN KEY (account_id)
        REFERENCES td_account (account_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_war_tenant_status ON td_workspace_access_request (tenant_id, status, created_at DESC);

CREATE TABLE td_subscription (
    subscription_id     VARCHAR(36)  NOT NULL,
    org_id              VARCHAR(36)  NOT NULL,
    plan_code           VARCHAR(32)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    seat_limit          INT      NOT NULL,
    workspace_limit     INT      NOT NULL,
    spec_limit          INT      NOT NULL,
    run_limit_month     INT      NOT NULL,
    trial_ends_at       DATETIME(3),
    current_period_end  DATETIME(3),
    created_at          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_subscription PRIMARY KEY (subscription_id),
    CONSTRAINT fk_td_subscription_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE UNIQUE INDEX ux_td_subscription_org ON td_subscription (org_id);

-- usage_month is YYYY-MM. (Not year_month, which is a reserved word in MySQL.)
CREATE TABLE td_usage_month (
    org_id       VARCHAR(36)  NOT NULL,
    usage_month  VARCHAR(7)   NOT NULL,
    run_count    INT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_td_usage_month PRIMARY KEY (org_id, usage_month),
    CONSTRAINT fk_td_usage_month_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE td_audit_event (
    event_id          VARCHAR(36)   NOT NULL,
    tenant_id         VARCHAR(36)   NOT NULL,
    spec_id           VARCHAR(36),
    resource_type     VARCHAR(32)   NOT NULL,
    resource_id       VARCHAR(36)   NOT NULL,
    action            VARCHAR(32)   NOT NULL,
    actor_account_id  VARCHAR(36),
    actor_label       VARCHAR(320),
    detail            VARCHAR(512),
    occurred_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_audit_event PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_audit_tenant_spec_time ON td_audit_event (tenant_id, spec_id, occurred_at DESC);
CREATE INDEX ix_td_audit_tenant_resource_time ON td_audit_event (tenant_id, resource_type, resource_id, occurred_at DESC);

CREATE TABLE td_global_variables (
    tenant_id      VARCHAR(36)  NOT NULL,
    document_json  LONGTEXT         NOT NULL,
    updated_at     DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_global_variables PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- ---------- Collections, plans, environments ----------

CREATE TABLE td_spec (
    spec_id          VARCHAR(36)    NOT NULL,
    tenant_id        VARCHAR(36),
    name             VARCHAR(255),
    label            VARCHAR(255),
    title            VARCHAR(255)   NOT NULL,
    version          VARCHAR(64),
    description      LONGTEXT,
    base_url         VARCHAR(1024),
    source_location  VARCHAR(2048),
    source_content   LONGTEXT,
    operation_count  INT        NOT NULL DEFAULT 0,
    created_at       DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_spec PRIMARY KEY (spec_id),
    CONSTRAINT fk_td_spec_tenant FOREIGN KEY (tenant_id)
        REFERENCES td_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE UNIQUE INDEX ux_td_spec_tenant_name ON td_spec (tenant_id, name);
CREATE INDEX ix_td_spec_tenant ON td_spec (tenant_id);

CREATE TABLE td_test_plan (
    plan_id      VARCHAR(36)   NOT NULL,
    spec_id      VARCHAR(36)   NOT NULL,
    name         VARCHAR(255)  NOT NULL,
    description  LONGTEXT,
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_test_plan PRIMARY KEY (plan_id),
    CONSTRAINT fk_td_test_plan_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_test_plan_spec ON td_test_plan (spec_id);

CREATE TABLE td_environment (
    environment_id  VARCHAR(36)   NOT NULL,
    spec_id         VARCHAR(36)   NOT NULL,
    name            VARCHAR(255)  NOT NULL,
    document_json   LONGTEXT          NOT NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_environment PRIMARY KEY (environment_id),
    CONSTRAINT fk_td_environment_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_environment_spec ON td_environment (spec_id);
CREATE UNIQUE INDEX ux_td_environment_spec_name ON td_environment (spec_id, name);

CREATE TABLE td_private_target_allowlist (
    environment_id  VARCHAR(36)  NOT NULL,
    entries         LONGTEXT         NOT NULL,
    updated_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_private_target_allowlist PRIMARY KEY (environment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- ---------- Test cases ----------

CREATE TABLE td_test_case (
    test_case_id           VARCHAR(36)    NOT NULL,
    spec_id                VARCHAR(36)    NOT NULL,
    plan_id                VARCHAR(36),
    operation_key          VARCHAR(255)   NOT NULL,
    test_name              VARCHAR(255)   NOT NULL,
    http_method            VARCHAR(16)    NOT NULL,
    raw_path               VARCHAR(1024)  NOT NULL,
    endpoint               VARCHAR(1024)  NOT NULL,
    base_url               VARCHAR(1024),
    content_type           VARCHAR(64),
    request_body_json      LONGTEXT,
    response_schema_json   LONGTEXT,
    status_code            INT        NOT NULL,
    response_type          VARCHAR(32),
    expected_response      LONGTEXT,
    enabled                VARCHAR(8)     NOT NULL DEFAULT 'Y',
    test_group             VARCHAR(128),
    json_path_assertions   LONGTEXT,
    extracts               LONGTEXT,
    max_response_time_ms   BIGINT,
    environment_names      LONGTEXT,
    created_at             DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at             DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by_account_id  VARCHAR(36),
    created_by_label       VARCHAR(320),
    updated_by_account_id  VARCHAR(36),
    updated_by_label       VARCHAR(320),
    lifecycle              VARCHAR(16)    NOT NULL DEFAULT 'READY',
    response_match         VARCHAR(16),
    ignore_fields          LONGTEXT,
    case_auth              LONGTEXT,
    CONSTRAINT pk_td_test_case PRIMARY KEY (test_case_id),
    CONSTRAINT fk_td_test_case_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_test_case_spec ON td_test_case (spec_id);
CREATE INDEX ix_td_test_case_operation ON td_test_case (spec_id, operation_key);
CREATE INDEX ix_td_test_case_plan ON td_test_case (plan_id);

CREATE TABLE td_test_case_param (
    test_case_id  VARCHAR(36)   NOT NULL,
    param_in      VARCHAR(16)   NOT NULL,
    param_name    VARCHAR(255)  NOT NULL,
    param_value   LONGTEXT,
    CONSTRAINT pk_td_test_case_param PRIMARY KEY (test_case_id, param_in, param_name),
    CONSTRAINT fk_td_test_case_param_case FOREIGN KEY (test_case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE,
    CONSTRAINT ck_td_test_case_param_in CHECK (param_in IN ('path', 'query', 'header', 'cookie'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_test_case_param_in ON td_test_case_param (param_in);

CREATE TABLE td_request_file (
    file_id       VARCHAR(36)   NOT NULL,
    test_case_id  VARCHAR(36)   NOT NULL,
    file_name     VARCHAR(512)  NOT NULL,
    content_type  VARCHAR(255),
    size_bytes    BIGINT        NOT NULL,
    content       LONGBLOB         NOT NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_request_file PRIMARY KEY (file_id),
    CONSTRAINT fk_td_request_file_case FOREIGN KEY (test_case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_request_file_case ON td_request_file (test_case_id);

-- Unpublished changes to a published case. The case row keeps the published version.
CREATE TABLE td_test_case_draft (
    test_case_id      VARCHAR(36)   NOT NULL,
    document_json     LONGTEXT          NOT NULL,
    base_updated_at   DATETIME(3),
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by_label  VARCHAR(255),
    CONSTRAINT pk_td_test_case_draft PRIMARY KEY (test_case_id),
    CONSTRAINT fk_td_test_case_draft_case FOREIGN KEY (test_case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- A copy of every published state, for Version history and Restore.
CREATE TABLE td_test_case_version (
    version_id          VARCHAR(36)   NOT NULL,
    test_case_id        VARCHAR(36)   NOT NULL,
    document_json       LONGTEXT          NOT NULL,
    published_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_by_label  VARCHAR(255),
    CONSTRAINT pk_td_test_case_version PRIMARY KEY (version_id),
    CONSTRAINT fk_td_test_case_version_case FOREIGN KEY (test_case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_test_case_version_case ON td_test_case_version (test_case_id, published_at);

-- ---------- Run history ----------

CREATE TABLE td_run (
    run_id          VARCHAR(36)  NOT NULL,
    spec_id         VARCHAR(36)  NOT NULL,
    plan_id         VARCHAR(36),
    run_trigger     VARCHAR(16)  NOT NULL,
    passed          INT      NOT NULL DEFAULT 0,
    failed          INT      NOT NULL DEFAULT 0,
    skipped         INT      NOT NULL DEFAULT 0,
    started_at      DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    environment_id  VARCHAR(36),
    CONSTRAINT pk_td_run PRIMARY KEY (run_id),
    CONSTRAINT fk_td_run_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_run_spec ON td_run (spec_id, started_at);
CREATE INDEX ix_td_run_spec_plan_env ON td_run (spec_id, plan_id, environment_id, started_at);

CREATE TABLE td_run_result (
    result_id             VARCHAR(36)    NOT NULL,
    run_id                VARCHAR(36)    NOT NULL,
    spec_id               VARCHAR(36)    NOT NULL,
    plan_id               VARCHAR(36),
    case_id               VARCHAR(36),
    operation_key         VARCHAR(255),
    test_name             VARCHAR(255),
    run_trigger           VARCHAR(16)    NOT NULL,
    outcome               VARCHAR(16)    NOT NULL,
    method                VARCHAR(16),
    url                   VARCHAR(2048),
    expected_status       INT,
    actual_status         INT,
    status_matched        BOOLEAN,
    elapsed_ms            BIGINT,
    max_response_time_ms  BIGINT,
    timed_out             BOOLEAN,
    error                 LONGTEXT,
    request_headers       LONGTEXT,
    request_body          LONGTEXT,
    request_content_type  VARCHAR(64),
    request_params        LONGTEXT,
    response_headers      LONGTEXT,
    response_body         LONGTEXT,
    assertions_json       LONGTEXT,
    download_path         VARCHAR(2048),
    created_at            DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_run_result PRIMARY KEY (result_id),
    CONSTRAINT fk_td_run_result_run FOREIGN KEY (run_id)
        REFERENCES td_run (run_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_run_result_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_run_result_case FOREIGN KEY (case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_run_result_run ON td_run_result (run_id);
CREATE INDEX ix_td_run_result_spec ON td_run_result (spec_id, created_at);
CREATE INDEX ix_td_run_result_case ON td_run_result (case_id);
