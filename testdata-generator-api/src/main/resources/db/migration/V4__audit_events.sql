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
