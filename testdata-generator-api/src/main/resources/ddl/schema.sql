-- Test Data Studio schema
-- Used for testdata.storage.type = h2 and testdata.storage.type = database
-- Compatible with PostgreSQL 12+ and H2 2.x (MODE=PostgreSQL; DATABASE_TO_LOWER=TRUE)

CREATE TABLE IF NOT EXISTS td_spec (
    spec_id           VARCHAR(36)   NOT NULL,
    name              VARCHAR(255),
    title             VARCHAR(255)  NOT NULL,
    version           VARCHAR(64),
    description       TEXT,
    base_url          VARCHAR(1024),
    source_location   VARCHAR(2048),
    source_content    TEXT,
    operation_count   INTEGER       NOT NULL DEFAULT 0,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_spec PRIMARY KEY (spec_id)
);

CREATE TABLE IF NOT EXISTS td_test_case (
    test_case_id           VARCHAR(36)   NOT NULL,
    spec_id                VARCHAR(36)   NOT NULL,
    operation_key          VARCHAR(255)  NOT NULL,
    test_name              VARCHAR(255)  NOT NULL,
    http_method            VARCHAR(16)   NOT NULL,
    raw_path               VARCHAR(1024) NOT NULL,
    endpoint               VARCHAR(1024) NOT NULL,
    base_url               VARCHAR(1024),
    content_type           VARCHAR(64),
    request_body_json      TEXT,
    response_schema_json   TEXT,
    status_code            INTEGER       NOT NULL,
    response_type          VARCHAR(32),
    expected_response      TEXT,
    enabled                VARCHAR(8)    NOT NULL DEFAULT 'Y',
    test_group             VARCHAR(128),
    json_path_assertions   TEXT,
    max_response_time_ms   BIGINT,
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_test_case PRIMARY KEY (test_case_id),
    CONSTRAINT fk_td_test_case_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS td_test_plan (
    plan_id      VARCHAR(36)   NOT NULL,
    spec_id      VARCHAR(36)   NOT NULL,
    name         VARCHAR(255)  NOT NULL,
    description  TEXT,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_test_plan PRIMARY KEY (plan_id),
    CONSTRAINT fk_td_test_plan_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
);

ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS plan_id VARCHAR(36);

ALTER TABLE td_spec ADD COLUMN IF NOT EXISTS name VARCHAR(255);

UPDATE td_spec
   SET name = COALESCE(NULLIF(TRIM(name), ''), NULLIF(TRIM(title), ''), spec_id)
 WHERE name IS NULL OR TRIM(name) = '';

CREATE TABLE IF NOT EXISTS td_test_case_param (
    test_case_id  VARCHAR(36)  NOT NULL,
    param_in      VARCHAR(16)  NOT NULL,
    param_name    VARCHAR(255) NOT NULL,
    param_value   TEXT,
    CONSTRAINT pk_td_test_case_param PRIMARY KEY (test_case_id, param_in, param_name),
    CONSTRAINT fk_td_test_case_param_case FOREIGN KEY (test_case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE,
    CONSTRAINT ck_td_test_case_param_in CHECK (param_in IN ('path', 'query', 'header', 'cookie'))
);

ALTER TABLE td_test_case_param DROP CONSTRAINT IF EXISTS ck_td_test_case_param_in;
ALTER TABLE td_test_case_param ADD CONSTRAINT ck_td_test_case_param_in
    CHECK (param_in IN ('path', 'query', 'header', 'cookie'));

CREATE INDEX IF NOT EXISTS ix_td_test_case_spec
    ON td_test_case (spec_id);

CREATE INDEX IF NOT EXISTS ix_td_test_case_operation
    ON td_test_case (spec_id, operation_key);

CREATE INDEX IF NOT EXISTS ix_td_test_plan_spec
    ON td_test_plan (spec_id);

CREATE INDEX IF NOT EXISTS ix_td_test_case_plan
    ON td_test_case (plan_id);

CREATE INDEX IF NOT EXISTS ix_td_test_case_param_in
    ON td_test_case_param (param_in);

CREATE TABLE IF NOT EXISTS td_request_file (
    file_id       VARCHAR(36)   NOT NULL,
    test_case_id  VARCHAR(36)   NOT NULL,
    file_name     VARCHAR(512)  NOT NULL,
    content_type  VARCHAR(255),
    size_bytes    BIGINT        NOT NULL,
    content       BYTEA         NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_request_file PRIMARY KEY (file_id),
    CONSTRAINT fk_td_request_file_case FOREIGN KEY (test_case_id)
        REFERENCES td_test_case (test_case_id) ON DELETE CASCADE
);

ALTER TABLE td_request_file ADD COLUMN IF NOT EXISTS test_case_id VARCHAR(36);
DELETE FROM td_request_file WHERE test_case_id IS NULL;
ALTER TABLE td_request_file DROP CONSTRAINT IF EXISTS fk_td_request_file_spec;
DROP INDEX IF EXISTS ix_td_request_file_spec;
ALTER TABLE td_request_file DROP COLUMN IF EXISTS spec_id;
ALTER TABLE td_request_file ALTER COLUMN test_case_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_td_request_file_case
    ON td_request_file (test_case_id);
