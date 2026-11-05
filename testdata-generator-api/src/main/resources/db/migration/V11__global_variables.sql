CREATE TABLE IF NOT EXISTS td_global_variables (
    tenant_id      VARCHAR(36)  NOT NULL,
    document_json  TEXT         NOT NULL,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_global_variables PRIMARY KEY (tenant_id)
);
