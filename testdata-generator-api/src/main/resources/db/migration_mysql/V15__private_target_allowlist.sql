CREATE TABLE IF NOT EXISTS td_private_target_allowlist (
    environment_id VARCHAR(36)  NOT NULL,
    entries        TEXT         NOT NULL,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_private_target_allowlist PRIMARY KEY (environment_id)
);
