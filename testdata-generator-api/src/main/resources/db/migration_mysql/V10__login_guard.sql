ALTER TABLE td_session ADD COLUMN last_seen_at TIMESTAMP NULL;

UPDATE td_session
   SET last_seen_at = CURRENT_TIMESTAMP
 WHERE last_seen_at IS NULL;

CREATE TABLE IF NOT EXISTS td_login_guard (
    subject            VARCHAR(320) NOT NULL,
    attempt_count      INT          NOT NULL,
    window_started_at  TIMESTAMP    NOT NULL,
    locked_until       TIMESTAMP    NULL,
    PRIMARY KEY (subject)
);
