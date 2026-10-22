CREATE TABLE IF NOT EXISTS td_workspace_access_request (
    request_id   VARCHAR(36)  NOT NULL,
    tenant_id    VARCHAR(36)  NOT NULL,
    account_id   VARCHAR(36)  NOT NULL,
    message      VARCHAR(2000),
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_workspace_access_request PRIMARY KEY (request_id),
    CONSTRAINT fk_td_war_tenant FOREIGN KEY (tenant_id)
        REFERENCES td_tenant (tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_war_account FOREIGN KEY (account_id)
        REFERENCES td_account (account_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_td_war_tenant_status
    ON td_workspace_access_request (tenant_id, status, created_at DESC);
