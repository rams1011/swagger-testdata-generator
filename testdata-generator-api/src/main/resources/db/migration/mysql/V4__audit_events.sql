CREATE TABLE IF NOT EXISTS td_audit_event (
    event_id           VARCHAR(36)   NOT NULL,
    tenant_id          VARCHAR(36)   NOT NULL,
    spec_id            VARCHAR(36)   NULL,
    resource_type      VARCHAR(32)   NOT NULL,
    resource_id        VARCHAR(36)   NOT NULL,
    action             VARCHAR(32)   NOT NULL,
    actor_account_id   VARCHAR(36)   NULL,
    actor_label        VARCHAR(320)  NULL,
    detail             VARCHAR(512)  NULL,
    occurred_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_audit_event PRIMARY KEY (event_id)
);

CREATE INDEX ix_td_audit_tenant_spec_time
    ON td_audit_event (tenant_id, spec_id, occurred_at DESC);

CREATE INDEX ix_td_audit_tenant_resource_time
    ON td_audit_event (tenant_id, resource_type, resource_id, occurred_at DESC);
