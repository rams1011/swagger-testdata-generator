-- SQL Server DDL for Test Data Studio
-- Database: testdata_studio (create separately)
--
--   CREATE DATABASE testdata_studio;
--   -- optional login:
--   CREATE LOGIN testdata WITH PASSWORD = 'ChangeMe!';
--   USE testdata_studio;
--   CREATE USER testdata FOR LOGIN testdata;
--   ALTER ROLE db_owner ADD MEMBER testdata;
--
-- Requires SQL Server 2017+ (TRIM). Run against an empty or new database.
-- Keep aligned with schema.sql and postgresql.sql.
-- Note: Studio API JDBC is tested with PostgreSQL/H2; use this DDL for manual
-- provisioning or custom JDBC (com.microsoft.sqlserver:mssql-jdbc).

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;

/* ---------- td_tenant ---------- */
IF OBJECT_ID(N'dbo.td_tenant', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_tenant (
        tenant_id   VARCHAR(36)   NOT NULL,
        slug        VARCHAR(64)   NOT NULL,
        name        NVARCHAR(255) NOT NULL,
        ready       BIT           NOT NULL CONSTRAINT DF_td_tenant_ready DEFAULT (0),
        enabled_at  DATETIME2(3)  NULL,
        org_id      VARCHAR(36)   NULL,
        created_at  DATETIME2(3)  NOT NULL CONSTRAINT DF_td_tenant_created_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_tenant PRIMARY KEY (tenant_id)
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ux_td_tenant_slug' AND object_id = OBJECT_ID(N'dbo.td_tenant')
)
    CREATE UNIQUE INDEX ux_td_tenant_slug ON dbo.td_tenant (slug);

INSERT INTO dbo.td_tenant (tenant_id, slug, name, created_at)
SELECT '00000000-0000-0000-0000-000000000001', 'local', N'Local', SYSUTCDATETIME()
 WHERE NOT EXISTS (
     SELECT 1 FROM dbo.td_tenant WHERE tenant_id = '00000000-0000-0000-0000-000000000001'
 );

INSERT INTO dbo.td_tenant (tenant_id, slug, name, created_at)
SELECT '00000000-0000-0000-0000-000000000002', 'saas-platform', N'SaaS', SYSUTCDATETIME()
 WHERE NOT EXISTS (
     SELECT 1 FROM dbo.td_tenant WHERE tenant_id = '00000000-0000-0000-0000-000000000002'
 );

UPDATE dbo.td_tenant
   SET ready = 1
 WHERE tenant_id IN (
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000002'
 );

/* ---------- td_account ---------- */
IF OBJECT_ID(N'dbo.td_account', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_account (
        account_id     VARCHAR(36)   NOT NULL,
        email          VARCHAR(320)  NOT NULL,
        password_hash  VARCHAR(255)  NOT NULL,
        display_name   NVARCHAR(255) NOT NULL,
        platform_role  VARCHAR(16)   NOT NULL CONSTRAINT DF_td_account_platform_role DEFAULT ('USER'),
        created_at     DATETIME2(3)  NOT NULL CONSTRAINT DF_td_account_created_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_account PRIMARY KEY (account_id)
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ux_td_account_email' AND object_id = OBJECT_ID(N'dbo.td_account')
)
    CREATE UNIQUE INDEX ux_td_account_email ON dbo.td_account (email);

IF COL_LENGTH('dbo.td_account', 'password_changed_at') IS NULL
    ALTER TABLE dbo.td_account ADD password_changed_at DATETIME2(3) NULL;
IF COL_LENGTH('dbo.td_account', 'must_change_password') IS NULL
    ALTER TABLE dbo.td_account ADD must_change_password BIT NOT NULL
        CONSTRAINT DF_td_account_must_change DEFAULT (0);

IF OBJECT_ID(N'dbo.td_password_history', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_password_history (
        history_id     VARCHAR(36)   NOT NULL,
        account_id     VARCHAR(36)   NOT NULL,
        password_hash  VARCHAR(255)  NOT NULL,
        retired_at     DATETIME2(3)  NOT NULL,
        CONSTRAINT pk_td_password_history PRIMARY KEY (history_id),
        CONSTRAINT fk_td_password_history_account FOREIGN KEY (account_id)
            REFERENCES dbo.td_account (account_id) ON DELETE CASCADE
    );
    CREATE INDEX ix_td_password_history_account ON dbo.td_password_history (account_id, retired_at);
END;

/* ---------- td_membership ---------- */
IF OBJECT_ID(N'dbo.td_membership', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_membership (
        tenant_id   VARCHAR(36)  NOT NULL,
        account_id  VARCHAR(36)  NOT NULL,
        role        VARCHAR(16)  NOT NULL,
        created_at  DATETIME2(3) NOT NULL CONSTRAINT DF_td_membership_created_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_membership PRIMARY KEY (tenant_id, account_id),
        CONSTRAINT fk_td_membership_tenant FOREIGN KEY (tenant_id)
            REFERENCES dbo.td_tenant (tenant_id) ON DELETE CASCADE,
        CONSTRAINT fk_td_membership_account FOREIGN KEY (account_id)
            REFERENCES dbo.td_account (account_id) ON DELETE CASCADE
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_membership_account' AND object_id = OBJECT_ID(N'dbo.td_membership')
)
    CREATE INDEX ix_td_membership_account ON dbo.td_membership (account_id);

/* ---------- td_session ---------- */
IF OBJECT_ID(N'dbo.td_session', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_session (
        token_hash   VARCHAR(64)  NOT NULL,
        account_id   VARCHAR(36)  NOT NULL,
        tenant_id    VARCHAR(36)  NOT NULL,
        expires_at   DATETIME2(3) NOT NULL,
        created_at   DATETIME2(3) NOT NULL CONSTRAINT DF_td_session_created_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_session PRIMARY KEY (token_hash),
        CONSTRAINT fk_td_session_account FOREIGN KEY (account_id)
            REFERENCES dbo.td_account (account_id) ON DELETE CASCADE,
        CONSTRAINT fk_td_session_tenant FOREIGN KEY (tenant_id)
            REFERENCES dbo.td_tenant (tenant_id) ON DELETE CASCADE
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_session_account' AND object_id = OBJECT_ID(N'dbo.td_session')
)
    CREATE INDEX ix_td_session_account ON dbo.td_session (account_id);

/* ---------- td_spec ---------- */
IF OBJECT_ID(N'dbo.td_spec', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_spec (
        spec_id           VARCHAR(36)   NOT NULL,
        tenant_id         VARCHAR(36)   NULL,
        name              VARCHAR(255)  NULL,
        label             VARCHAR(255)  NULL,
        title             NVARCHAR(255) NOT NULL,
        version           VARCHAR(64)   NULL,
        description       NVARCHAR(MAX) NULL,
        base_url          VARCHAR(1024) NULL,
        source_location   VARCHAR(2048) NULL,
        source_content    NVARCHAR(MAX) NULL,
        operation_count   INT           NOT NULL CONSTRAINT DF_td_spec_operation_count DEFAULT (0),
        created_at        DATETIME2(3)  NOT NULL CONSTRAINT DF_td_spec_created_at DEFAULT (SYSUTCDATETIME()),
        updated_at        DATETIME2(3)  NOT NULL CONSTRAINT DF_td_spec_updated_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_spec PRIMARY KEY (spec_id),
        CONSTRAINT fk_td_spec_tenant FOREIGN KEY (tenant_id)
            REFERENCES dbo.td_tenant (tenant_id)
    );
END;

UPDATE dbo.td_spec
   SET tenant_id = '00000000-0000-0000-0000-000000000001'
 WHERE tenant_id IS NULL OR LTRIM(RTRIM(tenant_id)) = '';

UPDATE dbo.td_spec
   SET name = COALESCE(NULLIF(LTRIM(RTRIM(name)), ''), NULLIF(LTRIM(RTRIM(title)), ''), spec_id)
 WHERE name IS NULL OR LTRIM(RTRIM(name)) = '';

UPDATE dbo.td_spec
   SET label = 'General'
 WHERE label IS NULL OR LTRIM(RTRIM(label)) = '';

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ux_td_spec_tenant_name' AND object_id = OBJECT_ID(N'dbo.td_spec')
)
    CREATE UNIQUE INDEX ux_td_spec_tenant_name ON dbo.td_spec (tenant_id, name);

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_spec_tenant' AND object_id = OBJECT_ID(N'dbo.td_spec')
)
    CREATE INDEX ix_td_spec_tenant ON dbo.td_spec (tenant_id);

/* ---------- td_test_plan ---------- */
IF OBJECT_ID(N'dbo.td_test_plan', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_test_plan (
        plan_id      VARCHAR(36)   NOT NULL,
        spec_id      VARCHAR(36)   NOT NULL,
        name         NVARCHAR(255) NOT NULL,
        description  NVARCHAR(MAX) NULL,
        created_at   DATETIME2(3)  NOT NULL CONSTRAINT DF_td_test_plan_created_at DEFAULT (SYSUTCDATETIME()),
        updated_at   DATETIME2(3)  NOT NULL CONSTRAINT DF_td_test_plan_updated_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_test_plan PRIMARY KEY (plan_id),
        CONSTRAINT fk_td_test_plan_spec FOREIGN KEY (spec_id)
            REFERENCES dbo.td_spec (spec_id) ON DELETE CASCADE
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_test_plan_spec' AND object_id = OBJECT_ID(N'dbo.td_test_plan')
)
    CREATE INDEX ix_td_test_plan_spec ON dbo.td_test_plan (spec_id);

/* ---------- td_test_case ---------- */
IF OBJECT_ID(N'dbo.td_test_case', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_test_case (
        test_case_id           VARCHAR(36)   NOT NULL,
        spec_id                VARCHAR(36)   NOT NULL,
        plan_id                VARCHAR(36)   NULL,
        operation_key          VARCHAR(255)  NOT NULL,
        test_name              NVARCHAR(255) NOT NULL,
        http_method            VARCHAR(16)   NOT NULL,
        raw_path               VARCHAR(1024) NOT NULL,
        endpoint               VARCHAR(1024) NOT NULL,
        base_url               VARCHAR(1024) NULL,
        content_type           VARCHAR(64)   NULL,
        request_body_json      NVARCHAR(MAX) NULL,
        response_schema_json   NVARCHAR(MAX) NULL,
        status_code            INT           NOT NULL,
        response_type          VARCHAR(32)   NULL,
        expected_response      NVARCHAR(MAX) NULL,
        enabled                VARCHAR(8)    NOT NULL CONSTRAINT DF_td_test_case_enabled DEFAULT ('Y'),
        lifecycle              VARCHAR(16)   NOT NULL CONSTRAINT DF_td_test_case_lifecycle DEFAULT ('READY'),
        test_group             VARCHAR(128)  NULL,
        json_path_assertions   NVARCHAR(MAX) NULL,
        extracts               NVARCHAR(MAX) NULL,
        max_response_time_ms   BIGINT        NULL,
        environment_names      NVARCHAR(MAX) NULL,
        created_by_account_id  VARCHAR(36)   NULL,
        created_by_label       VARCHAR(320)  NULL,
        updated_by_account_id  VARCHAR(36)   NULL,
        updated_by_label       VARCHAR(320)  NULL,
        created_at             DATETIME2(3)  NOT NULL CONSTRAINT DF_td_test_case_created_at DEFAULT (SYSUTCDATETIME()),
        updated_at             DATETIME2(3)  NOT NULL CONSTRAINT DF_td_test_case_updated_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_test_case PRIMARY KEY (test_case_id),
        CONSTRAINT fk_td_test_case_spec FOREIGN KEY (spec_id)
            REFERENCES dbo.td_spec (spec_id) ON DELETE CASCADE
    );
END;

IF COL_LENGTH('dbo.td_test_case', 'lifecycle') IS NULL
    ALTER TABLE dbo.td_test_case ADD lifecycle VARCHAR(16) NOT NULL
        CONSTRAINT DF_td_test_case_lifecycle_add DEFAULT ('READY');

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_test_case_spec' AND object_id = OBJECT_ID(N'dbo.td_test_case')
)
    CREATE INDEX ix_td_test_case_spec ON dbo.td_test_case (spec_id);

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_test_case_operation' AND object_id = OBJECT_ID(N'dbo.td_test_case')
)
    CREATE INDEX ix_td_test_case_operation ON dbo.td_test_case (spec_id, operation_key);

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_test_case_plan' AND object_id = OBJECT_ID(N'dbo.td_test_case')
)
    CREATE INDEX ix_td_test_case_plan ON dbo.td_test_case (plan_id);

/* ---------- td_test_case_param ---------- */
IF OBJECT_ID(N'dbo.td_test_case_param', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_test_case_param (
        test_case_id  VARCHAR(36)  NOT NULL,
        param_in      VARCHAR(16)  NOT NULL,
        param_name    VARCHAR(255) NOT NULL,
        param_value   NVARCHAR(MAX) NULL,
        CONSTRAINT pk_td_test_case_param PRIMARY KEY (test_case_id, param_in, param_name),
        CONSTRAINT fk_td_test_case_param_case FOREIGN KEY (test_case_id)
            REFERENCES dbo.td_test_case (test_case_id) ON DELETE CASCADE,
        CONSTRAINT ck_td_test_case_param_in CHECK (param_in IN ('path', 'query', 'header', 'cookie'))
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_test_case_param_in' AND object_id = OBJECT_ID(N'dbo.td_test_case_param')
)
    CREATE INDEX ix_td_test_case_param_in ON dbo.td_test_case_param (param_in);

/* ---------- td_request_file ---------- */
IF OBJECT_ID(N'dbo.td_request_file', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_request_file (
        file_id       VARCHAR(36)   NOT NULL,
        test_case_id  VARCHAR(36)   NOT NULL,
        file_name     NVARCHAR(512) NOT NULL,
        content_type  VARCHAR(255)  NULL,
        size_bytes    BIGINT        NOT NULL,
        content       VARBINARY(MAX) NOT NULL,
        created_at    DATETIME2(3)  NOT NULL CONSTRAINT DF_td_request_file_created_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_request_file PRIMARY KEY (file_id),
        CONSTRAINT fk_td_request_file_case FOREIGN KEY (test_case_id)
            REFERENCES dbo.td_test_case (test_case_id) ON DELETE CASCADE
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_request_file_case' AND object_id = OBJECT_ID(N'dbo.td_request_file')
)
    CREATE INDEX ix_td_request_file_case ON dbo.td_request_file (test_case_id);

/* ---------- td_run ---------- */
IF OBJECT_ID(N'dbo.td_run', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_run (
        run_id          VARCHAR(36)  NOT NULL,
        spec_id         VARCHAR(36)  NOT NULL,
        plan_id         VARCHAR(36)  NULL,
        environment_id  VARCHAR(36)  NULL,
        run_trigger     VARCHAR(16)  NOT NULL,
        passed          INT          NOT NULL CONSTRAINT DF_td_run_passed DEFAULT (0),
        failed          INT          NOT NULL CONSTRAINT DF_td_run_failed DEFAULT (0),
        skipped         INT          NOT NULL CONSTRAINT DF_td_run_skipped DEFAULT (0),
        started_at      DATETIME2(3) NOT NULL CONSTRAINT DF_td_run_started_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_run PRIMARY KEY (run_id),
        CONSTRAINT fk_td_run_spec FOREIGN KEY (spec_id)
            REFERENCES dbo.td_spec (spec_id) ON DELETE CASCADE
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_run_spec' AND object_id = OBJECT_ID(N'dbo.td_run')
)
    CREATE INDEX ix_td_run_spec ON dbo.td_run (spec_id, started_at);

/* ---------- td_run_result ---------- */
IF OBJECT_ID(N'dbo.td_run_result', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_run_result (
        result_id              VARCHAR(36)   NOT NULL,
        run_id                 VARCHAR(36)   NOT NULL,
        spec_id                VARCHAR(36)   NOT NULL,
        plan_id                VARCHAR(36)   NULL,
        case_id                VARCHAR(36)   NULL,
        operation_key          VARCHAR(255)  NULL,
        test_name              NVARCHAR(255) NULL,
        run_trigger            VARCHAR(16)   NOT NULL,
        outcome                VARCHAR(16)   NOT NULL,
        method                 VARCHAR(16)   NULL,
        url                    VARCHAR(2048) NULL,
        expected_status        INT           NULL,
        actual_status          INT           NULL,
        status_matched         BIT           NULL,
        elapsed_ms             BIGINT        NULL,
        max_response_time_ms   BIGINT        NULL,
        timed_out              BIT           NULL,
        error                  NVARCHAR(MAX) NULL,
        request_headers        NVARCHAR(MAX) NULL,
        request_body           NVARCHAR(MAX) NULL,
        request_content_type   VARCHAR(64)   NULL,
        request_params         NVARCHAR(MAX) NULL,
        response_headers       NVARCHAR(MAX) NULL,
        response_body          NVARCHAR(MAX) NULL,
        assertions_json        NVARCHAR(MAX) NULL,
        download_path          VARCHAR(2048) NULL,
        created_at             DATETIME2(3)  NOT NULL CONSTRAINT DF_td_run_result_created_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_run_result PRIMARY KEY (result_id),
        CONSTRAINT fk_td_run_result_run FOREIGN KEY (run_id)
            REFERENCES dbo.td_run (run_id) ON DELETE CASCADE,
        CONSTRAINT fk_td_run_result_spec FOREIGN KEY (spec_id)
            REFERENCES dbo.td_spec (spec_id) ON DELETE CASCADE,
        CONSTRAINT fk_td_run_result_case FOREIGN KEY (case_id)
            REFERENCES dbo.td_test_case (test_case_id) ON DELETE SET NULL
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_run_result_run' AND object_id = OBJECT_ID(N'dbo.td_run_result')
)
    CREATE INDEX ix_td_run_result_run ON dbo.td_run_result (run_id);

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_run_result_spec' AND object_id = OBJECT_ID(N'dbo.td_run_result')
)
    CREATE INDEX ix_td_run_result_spec ON dbo.td_run_result (spec_id, created_at);

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_run_result_case' AND object_id = OBJECT_ID(N'dbo.td_run_result')
)
    CREATE INDEX ix_td_run_result_case ON dbo.td_run_result (case_id);

/* ---------- td_environment ---------- */
IF OBJECT_ID(N'dbo.td_environment', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_environment (
        environment_id VARCHAR(36)   NOT NULL,
        spec_id        VARCHAR(36)   NOT NULL,
        name           NVARCHAR(255) NOT NULL,
        document_json  NVARCHAR(MAX) NOT NULL,
        created_at     DATETIME2(3)  NOT NULL CONSTRAINT DF_td_environment_created_at DEFAULT (SYSUTCDATETIME()),
        updated_at     DATETIME2(3)  NOT NULL CONSTRAINT DF_td_environment_updated_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_environment PRIMARY KEY (environment_id),
        CONSTRAINT fk_td_environment_spec FOREIGN KEY (spec_id)
            REFERENCES dbo.td_spec (spec_id) ON DELETE CASCADE
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_environment_spec' AND object_id = OBJECT_ID(N'dbo.td_environment')
)
    CREATE INDEX ix_td_environment_spec ON dbo.td_environment (spec_id);

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ux_td_environment_spec_name' AND object_id = OBJECT_ID(N'dbo.td_environment')
)
    CREATE UNIQUE INDEX ux_td_environment_spec_name ON dbo.td_environment (spec_id, name);

/* ---------- td_organization ---------- */
IF OBJECT_ID(N'dbo.td_organization', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_organization (
        org_id      VARCHAR(36)   NOT NULL,
        slug        VARCHAR(64)   NOT NULL,
        name        NVARCHAR(255) NOT NULL,
        created_at  DATETIME2(3)  NOT NULL CONSTRAINT DF_td_organization_created_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_organization PRIMARY KEY (org_id)
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ux_td_organization_slug' AND object_id = OBJECT_ID(N'dbo.td_organization')
)
    CREATE UNIQUE INDEX ux_td_organization_slug ON dbo.td_organization (slug);

/* ---------- td_org_membership ---------- */
IF OBJECT_ID(N'dbo.td_org_membership', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_org_membership (
        org_id      VARCHAR(36)  NOT NULL,
        account_id  VARCHAR(36)  NOT NULL,
        role        VARCHAR(16)  NOT NULL,
        created_at  DATETIME2(3) NOT NULL CONSTRAINT DF_td_org_membership_created_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_org_membership PRIMARY KEY (org_id, account_id),
        CONSTRAINT fk_td_org_membership_org FOREIGN KEY (org_id)
            REFERENCES dbo.td_organization (org_id) ON DELETE CASCADE,
        CONSTRAINT fk_td_org_membership_account FOREIGN KEY (account_id)
            REFERENCES dbo.td_account (account_id) ON DELETE CASCADE
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_org_membership_account' AND object_id = OBJECT_ID(N'dbo.td_org_membership')
)
    CREATE INDEX ix_td_org_membership_account ON dbo.td_org_membership (account_id);

/* ---------- td_subscription ---------- */
IF OBJECT_ID(N'dbo.td_subscription', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_subscription (
        subscription_id     VARCHAR(36)  NOT NULL,
        org_id              VARCHAR(36)  NOT NULL,
        plan_code           VARCHAR(32)  NOT NULL,
        status              VARCHAR(32)  NOT NULL,
        seat_limit          INT          NOT NULL,
        workspace_limit     INT          NOT NULL,
        spec_limit          INT          NOT NULL,
        run_limit_month     INT          NOT NULL,
        trial_ends_at       DATETIME2(3) NULL,
        current_period_end  DATETIME2(3) NULL,
        created_at          DATETIME2(3) NOT NULL CONSTRAINT DF_td_subscription_created_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_subscription PRIMARY KEY (subscription_id),
        CONSTRAINT fk_td_subscription_org FOREIGN KEY (org_id)
            REFERENCES dbo.td_organization (org_id) ON DELETE CASCADE
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ux_td_subscription_org' AND object_id = OBJECT_ID(N'dbo.td_subscription')
)
    CREATE UNIQUE INDEX ux_td_subscription_org ON dbo.td_subscription (org_id);

/* ---------- td_usage_month ---------- */
IF OBJECT_ID(N'dbo.td_usage_month', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_usage_month (
        org_id      VARCHAR(36) NOT NULL,
        year_month  VARCHAR(7)  NOT NULL,
        run_count   INT         NOT NULL CONSTRAINT DF_td_usage_month_run_count DEFAULT (0),
        CONSTRAINT pk_td_usage_month PRIMARY KEY (org_id, year_month),
        CONSTRAINT fk_td_usage_month_org FOREIGN KEY (org_id)
            REFERENCES dbo.td_organization (org_id) ON DELETE CASCADE
    );
END;

/* ---------- org backfill from tenants ---------- */
INSERT INTO dbo.td_organization (org_id, slug, name, created_at)
SELECT t.tenant_id, t.slug, t.name, t.created_at
  FROM dbo.td_tenant t
 WHERE NOT EXISTS (SELECT 1 FROM dbo.td_organization o WHERE o.org_id = t.tenant_id);

UPDATE dbo.td_tenant
   SET org_id = tenant_id
 WHERE org_id IS NULL OR LTRIM(RTRIM(org_id)) = '';

INSERT INTO dbo.td_org_membership (org_id, account_id, role, created_at)
SELECT t.org_id, m.account_id, 'OWNER', m.created_at
  FROM dbo.td_membership m
  JOIN dbo.td_tenant t ON t.tenant_id = m.tenant_id
 WHERE m.role = 'OWNER'
   AND t.org_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM dbo.td_org_membership om
        WHERE om.org_id = t.org_id AND om.account_id = m.account_id
   );

INSERT INTO dbo.td_org_membership (org_id, account_id, role, created_at)
SELECT t.org_id, m.account_id, 'MEMBER', m.created_at
  FROM dbo.td_membership m
  JOIN dbo.td_tenant t ON t.tenant_id = m.tenant_id
 WHERE t.org_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1 FROM dbo.td_org_membership om
        WHERE om.org_id = t.org_id AND om.account_id = m.account_id
   );

INSERT INTO dbo.td_subscription (
    subscription_id, org_id, plan_code, status,
    seat_limit, workspace_limit, spec_limit, run_limit_month,
    created_at)
SELECT t.tenant_id, t.org_id,
       CASE
         WHEN t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 'enterprise'
         WHEN t.ready = 1 THEN 'team'
         ELSE 'trial'
       END,
       CASE
         WHEN t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 'active'
         WHEN t.ready = 1 THEN 'active'
         ELSE 'trial'
       END,
       CASE WHEN t.ready = 1 OR t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 10 ELSE 2 END,
       CASE WHEN t.ready = 1 OR t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 5 ELSE 1 END,
       CASE WHEN t.ready = 1 OR t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 50 ELSE 3 END,
       CASE WHEN t.ready = 1 OR t.tenant_id IN (
             '00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002') THEN 5000 ELSE 100 END,
       t.created_at
  FROM dbo.td_tenant t
 WHERE t.org_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM dbo.td_subscription s WHERE s.org_id = t.org_id);

IF NOT EXISTS (
    SELECT 1 FROM sys.foreign_keys
     WHERE name = N'fk_td_tenant_org' AND parent_object_id = OBJECT_ID(N'dbo.td_tenant')
)
    ALTER TABLE dbo.td_tenant ADD CONSTRAINT fk_td_tenant_org FOREIGN KEY (org_id)
        REFERENCES dbo.td_organization (org_id);

/* ---------- td_audit_event ---------- */
IF OBJECT_ID(N'dbo.td_audit_event', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.td_audit_event (
        event_id           VARCHAR(36)   NOT NULL,
        tenant_id          VARCHAR(36)   NOT NULL,
        spec_id            VARCHAR(36)   NULL,
        resource_type      VARCHAR(32)   NOT NULL,
        resource_id        VARCHAR(36)   NOT NULL,
        action             VARCHAR(32)   NOT NULL,
        actor_account_id   VARCHAR(36)   NULL,
        actor_label        VARCHAR(320)  NULL,
        detail             NVARCHAR(512) NULL,
        occurred_at        DATETIME2(3)  NOT NULL CONSTRAINT DF_td_audit_event_occurred_at DEFAULT (SYSUTCDATETIME()),
        CONSTRAINT pk_td_audit_event PRIMARY KEY (event_id)
    );
END;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_audit_tenant_spec_time' AND object_id = OBJECT_ID(N'dbo.td_audit_event')
)
    CREATE INDEX ix_td_audit_tenant_spec_time
        ON dbo.td_audit_event (tenant_id, spec_id, occurred_at DESC);

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
     WHERE name = N'ix_td_audit_tenant_resource_time' AND object_id = OBJECT_ID(N'dbo.td_audit_event')
)
    CREATE INDEX ix_td_audit_tenant_resource_time
        ON dbo.td_audit_event (tenant_id, resource_type, resource_id, occurred_at DESC);
