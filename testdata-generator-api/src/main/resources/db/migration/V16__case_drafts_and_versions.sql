CREATE TABLE IF NOT EXISTS td_test_case_draft (
    test_case_id     VARCHAR(36)  NOT NULL,
    document_json    TEXT         NOT NULL,
    base_updated_at  TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_label VARCHAR(255),
    CONSTRAINT pk_td_test_case_draft PRIMARY KEY (test_case_id),
    CONSTRAINT fk_td_test_case_draft_case FOREIGN KEY (test_case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS td_test_case_version (
    version_id         VARCHAR(36)  NOT NULL,
    test_case_id       VARCHAR(36)  NOT NULL,
    document_json      TEXT         NOT NULL,
    published_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by_label VARCHAR(255),
    CONSTRAINT pk_td_test_case_version PRIMARY KEY (version_id),
    CONSTRAINT fk_td_test_case_version_case FOREIGN KEY (test_case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_td_test_case_version_case
    ON td_test_case_version (test_case_id, published_at);
