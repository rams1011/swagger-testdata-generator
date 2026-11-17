-- Production safety (UI repo docs/PRODUCTION_SAFETY.md). Keep db/migration_mysql/V8 in step.
-- Checks use the CASE form: H2 evaluates IN lists with the connection that created the check (MigrationRulesTest).

-- One row per organization. Organizations that exist now start Off; one created later has no row and starts in Shadow.
CREATE TABLE td_safety_policy (
    org_id                   VARCHAR(36)   NOT NULL,
    mode                     VARCHAR(16)   NOT NULL,
    max_requests_per_run     INT           NOT NULL,
    max_requests_per_minute  INT           NOT NULL,
    max_write_data_rows      INT           NOT NULL,
    require_second_admin     BOOLEAN       NOT NULL,
    self_approval            VARCHAR(16)   NOT NULL,
    revision                 BIGINT        NOT NULL,
    updated_by               VARCHAR(36),
    updated_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_safety_policy PRIMARY KEY (org_id),
    CONSTRAINT fk_td_safety_policy_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT ck_td_safety_policy_mode CHECK (
        CASE mode WHEN 'off' THEN TRUE WHEN 'shadow' THEN TRUE WHEN 'enforce' THEN TRUE ELSE FALSE END),
    CONSTRAINT ck_td_safety_policy_self_approval CHECK (
        CASE self_approval WHEN 'allowed' THEN TRUE WHEN 'never' THEN TRUE ELSE FALSE END)
);

INSERT INTO td_safety_policy (org_id, mode, max_requests_per_run, max_requests_per_minute, max_write_data_rows,
                              require_second_admin, self_approval, revision, updated_at)
SELECT org_id, 'off', 200, 60, 1, FALSE, 'allowed', 1, CURRENT_TIMESTAMP FROM td_organization;

CREATE TABLE td_protected_target (
    target_id   VARCHAR(36)   NOT NULL,
    org_id      VARCHAR(36)   NOT NULL,
    kind        VARCHAR(8)    NOT NULL,
    pattern     VARCHAR(255)  NOT NULL,
    ports       VARCHAR(255),
    note        VARCHAR(200),
    created_by  VARCHAR(36),
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_protected_target PRIMARY KEY (target_id),
    CONSTRAINT fk_td_protected_target_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT ck_td_protected_target_kind CHECK (
        CASE kind WHEN 'host' THEN TRUE WHEN 'cidr' THEN TRUE ELSE FALSE END)
);

CREATE UNIQUE INDEX ux_td_protected_target_pattern ON td_protected_target (org_id, pattern);

CREATE TABLE td_operation_rule (
    rule_id         VARCHAR(36)   NOT NULL,
    org_id          VARCHAR(36)   NOT NULL,
    effect          VARCHAR(16)   NOT NULL,
    protocol        VARCHAR(16)   NOT NULL,
    http_method     VARCHAR(16),
    path_template   VARCHAR(500),
    operation_name  VARCHAR(255),
    target_id       VARCHAR(36),
    reason          VARCHAR(300)  NOT NULL,
    expires_at      TIMESTAMP,
    created_by      VARCHAR(36),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_operation_rule PRIMARY KEY (rule_id),
    CONSTRAINT fk_td_operation_rule_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_operation_rule_target FOREIGN KEY (target_id)
        REFERENCES td_protected_target (target_id) ON DELETE CASCADE,
    CONSTRAINT ck_td_operation_rule_effect CHECK (
        CASE effect WHEN 'treat_as_read' THEN TRUE WHEN 'block' THEN TRUE ELSE FALSE END),
    CONSTRAINT ck_td_operation_rule_protocol CHECK (
        CASE protocol WHEN 'rest' THEN TRUE WHEN 'graphql' THEN TRUE WHEN 'grpc' THEN TRUE ELSE FALSE END)
);

CREATE INDEX ix_td_operation_rule_org ON td_operation_rule (org_id);

-- A column, not part of document_json, so environment export and import never carry or change it.
ALTER TABLE td_environment ADD COLUMN is_protected BOOLEAN NOT NULL DEFAULT FALSE;

-- An approval covers one case, one published revision, one environment.
CREATE TABLE td_write_approval (
    approval_id     VARCHAR(36)   NOT NULL,
    org_id          VARCHAR(36)   NOT NULL,
    case_id         VARCHAR(36)   NOT NULL,
    case_revision   BIGINT        NOT NULL,
    environment_id  VARCHAR(36)   NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    reason          VARCHAR(500)  NOT NULL,
    requested_by    VARCHAR(36)   NOT NULL,
    requested_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by      VARCHAR(36),
    decided_at      TIMESTAMP,
    decision_note   VARCHAR(500),
    self_approved   BOOLEAN       NOT NULL DEFAULT FALSE,
    expires_at      TIMESTAMP,
    max_runs        INT,
    runs_used       INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_td_write_approval PRIMARY KEY (approval_id),
    CONSTRAINT fk_td_write_approval_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_write_approval_case FOREIGN KEY (case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_write_approval_environment FOREIGN KEY (environment_id)
        REFERENCES td_environment (environment_id) ON DELETE CASCADE,
    CONSTRAINT ck_td_write_approval_status CHECK (
        CASE status WHEN 'requested' THEN TRUE WHEN 'approved' THEN TRUE WHEN 'rejected' THEN TRUE
                    WHEN 'revoked' THEN TRUE WHEN 'expired' THEN TRUE WHEN 'ended' THEN TRUE
                    WHEN 'withdrawn' THEN TRUE ELSE FALSE END)
);

CREATE INDEX ix_td_write_approval_case ON td_write_approval (case_id, environment_id, status);
CREATE INDEX ix_td_write_approval_org ON td_write_approval (org_id, status);

CREATE TABLE td_emergency_access (
    access_id       VARCHAR(36)   NOT NULL,
    org_id          VARCHAR(36)   NOT NULL,
    environment_id  VARCHAR(36)   NOT NULL,
    reason          VARCHAR(500)  NOT NULL,
    granted_by      VARCHAR(36)   NOT NULL,
    confirmed_by    VARCHAR(36),
    starts_at       TIMESTAMP     NOT NULL,
    ends_at         TIMESTAMP     NOT NULL,
    max_requests    INT,
    requests_used   INT           NOT NULL DEFAULT 0,
    ended_by        VARCHAR(36),
    ended_at        TIMESTAMP,
    CONSTRAINT pk_td_emergency_access PRIMARY KEY (access_id),
    CONSTRAINT fk_td_emergency_access_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_emergency_access_environment FOREIGN KEY (environment_id)
        REFERENCES td_environment (environment_id) ON DELETE CASCADE
);

CREATE INDEX ix_td_emergency_access_org ON td_emergency_access (org_id, ends_at);
