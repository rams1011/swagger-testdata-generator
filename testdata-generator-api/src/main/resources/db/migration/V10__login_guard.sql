ALTER TABLE td_session ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;

UPDATE td_session
   SET last_seen_at = CURRENT_TIMESTAMP
 WHERE last_seen_at IS NULL;

CREATE TABLE IF NOT EXISTS td_login_guard (
    subject            VARCHAR(320) NOT NULL,
    attempt_count      INTEGER      NOT NULL,
    window_started_at  TIMESTAMP    NOT NULL,
    locked_until       TIMESTAMP    NULL,
    CONSTRAINT pk_td_login_guard PRIMARY KEY (subject)
);
