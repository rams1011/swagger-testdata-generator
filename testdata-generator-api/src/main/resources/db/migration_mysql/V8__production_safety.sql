-- Same as db/migration/V8__production_safety.sql (MySQL types). MySQL rejects the CASE form in checks (error 3812),
-- so checks use IN here.

CREATE TABLE td_safety_policy (
    org_id                   VARCHAR(36)   NOT NULL,
    mode                     VARCHAR(16)   NOT NULL,
    max_requests_per_run     INT           NOT NULL,
    max_requests_per_minute  INT           NOT NULL,
    max_write_data_rows      INT           NOT NULL,
    require_second_admin     BOOLEAN       NOT NULL,
    self_approval            VARCHAR(16)   NOT NULL,
    revision                 BIGINT        NOT NULL,
    updated_by               VARCHAR(36)   NULL,
    updated_at               DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_safety_policy PRIMARY KEY (org_id),
    CONSTRAINT fk_td_safety_policy_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT ck_td_safety_policy_mode CHECK (mode IN ('off', 'shadow', 'enforce')),
    CONSTRAINT ck_td_safety_policy_self_approval CHECK (self_approval IN ('allowed', 'never'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

INSERT INTO td_safety_policy (org_id, mode, max_requests_per_run, max_requests_per_minute, max_write_data_rows,
                              require_second_admin, self_approval, revision, updated_at)
SELECT org_id, 'off', 200, 60, 1, FALSE, 'allowed', 1, CURRENT_TIMESTAMP(3) FROM td_organization;

CREATE TABLE td_protected_target (
    target_id   VARCHAR(36)   NOT NULL,
    org_id      VARCHAR(36)   NOT NULL,
    kind        VARCHAR(8)    NOT NULL,
    pattern     VARCHAR(255)  NOT NULL,
    ports       VARCHAR(255)  NULL,
    note        VARCHAR(200)  NULL,
    created_by  VARCHAR(36)   NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_protected_target PRIMARY KEY (target_id),
    CONSTRAINT fk_td_protected_target_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT ck_td_protected_target_kind CHECK (kind IN ('host', 'cidr'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE UNIQUE INDEX ux_td_protected_target_pattern ON td_protected_target (org_id, pattern);

CREATE TABLE td_operation_rule (
    rule_id         VARCHAR(36)   NOT NULL,
    org_id          VARCHAR(36)   NOT NULL,
    effect          VARCHAR(16)   NOT NULL,
    protocol        VARCHAR(16)   NOT NULL,
    http_method     VARCHAR(16)   NULL,
    path_template   VARCHAR(500)  NULL,
    operation_name  VARCHAR(255)  NULL,
    target_id       VARCHAR(36)   NULL,
    reason          VARCHAR(300)  NOT NULL,
    expires_at      DATETIME(3)   NULL,
    created_by      VARCHAR(36)   NULL,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_operation_rule PRIMARY KEY (rule_id),
    CONSTRAINT fk_td_operation_rule_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_operation_rule_target FOREIGN KEY (target_id)
        REFERENCES td_protected_target (target_id) ON DELETE CASCADE,
    CONSTRAINT ck_td_operation_rule_effect CHECK (effect IN ('treat_as_read', 'block')),
    CONSTRAINT ck_td_operation_rule_protocol CHECK (protocol IN ('rest', 'graphql', 'grpc'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_operation_rule_org ON td_operation_rule (org_id);

ALTER TABLE td_environment ADD COLUMN is_protected BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE td_write_approval (
    approval_id     VARCHAR(36)   NOT NULL,
    org_id          VARCHAR(36)   NOT NULL,
    case_id         VARCHAR(36)   NOT NULL,
    case_revision   BIGINT        NOT NULL,
    environment_id  VARCHAR(36)   NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    reason          VARCHAR(500)  NOT NULL,
    requested_by    VARCHAR(36)   NOT NULL,
    requested_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    decided_by      VARCHAR(36)   NULL,
    decided_at      DATETIME(3)   NULL,
    decision_note   VARCHAR(500)  NULL,
    self_approved   BOOLEAN       NOT NULL DEFAULT FALSE,
    expires_at      DATETIME(3)   NULL,
    max_runs        INT           NULL,
    runs_used       INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_td_write_approval PRIMARY KEY (approval_id),
    CONSTRAINT fk_td_write_approval_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_write_approval_case FOREIGN KEY (case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_write_approval_environment FOREIGN KEY (environment_id)
        REFERENCES td_environment (environment_id) ON DELETE CASCADE,
    CONSTRAINT ck_td_write_approval_status CHECK (
        status IN ('requested', 'approved', 'rejected', 'revoked', 'expired', 'ended', 'withdrawn'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_write_approval_case ON td_write_approval (case_id, environment_id, status);
CREATE INDEX ix_td_write_approval_org ON td_write_approval (org_id, status);

CREATE TABLE td_emergency_access (
    access_id       VARCHAR(36)   NOT NULL,
    org_id          VARCHAR(36)   NOT NULL,
    environment_id  VARCHAR(36)   NOT NULL,
    reason          VARCHAR(500)  NOT NULL,
    granted_by      VARCHAR(36)   NOT NULL,
    confirmed_by    VARCHAR(36)   NULL,
    starts_at       DATETIME(3)   NOT NULL,
    ends_at         DATETIME(3)   NOT NULL,
    max_requests    INT           NULL,
    requests_used   INT           NOT NULL DEFAULT 0,
    ended_by        VARCHAR(36)   NULL,
    ended_at        DATETIME(3)   NULL,
    CONSTRAINT pk_td_emergency_access PRIMARY KEY (access_id),
    CONSTRAINT fk_td_emergency_access_org FOREIGN KEY (org_id)
        REFERENCES td_organization (org_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_emergency_access_environment FOREIGN KEY (environment_id)
        REFERENCES td_environment (environment_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX ix_td_emergency_access_org ON td_emergency_access (org_id, ends_at);
