ALTER TABLE td_account ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP;
ALTER TABLE td_account ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE td_account
   SET password_changed_at = CURRENT_TIMESTAMP
 WHERE password_changed_at IS NULL
   AND password_hash <> 'ldap$external';

CREATE TABLE IF NOT EXISTS td_password_history (
    history_id     VARCHAR(36)   NOT NULL,
    account_id     VARCHAR(36)   NOT NULL,
    password_hash  VARCHAR(255)  NOT NULL,
    retired_at     TIMESTAMP     NOT NULL,
    CONSTRAINT pk_td_password_history PRIMARY KEY (history_id),
    CONSTRAINT fk_td_password_history_account FOREIGN KEY (account_id)
        REFERENCES td_account (account_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_td_password_history_account ON td_password_history (account_id, retired_at);
