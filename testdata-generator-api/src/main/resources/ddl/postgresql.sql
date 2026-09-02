-- PostgreSQL DDL for Test Data Studio
-- Database: testdata_studio
--
--   CREATE DATABASE testdata_studio;
--   CREATE USER testdata WITH PASSWORD 'testdata';
--   GRANT ALL PRIVILEGES ON DATABASE testdata_studio TO testdata;

CREATE TABLE IF NOT EXISTS td_spec (
    spec_id           VARCHAR(36)   PRIMARY KEY,
    name              VARCHAR(255),
    title             VARCHAR(255)  NOT NULL,
    version           VARCHAR(64),
    description       TEXT,
    base_url          VARCHAR(1024),
    source_location   VARCHAR(2048),
    source_content    TEXT,
    operation_count   INTEGER       NOT NULL DEFAULT 0,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE td_spec IS 'Loaded OpenAPI/Swagger specifications used to generate dynamic test pages';
COMMENT ON COLUMN td_spec.source_content IS 'Raw OpenAPI JSON or YAML so the spec can be reloaded after restart';
COMMENT ON COLUMN td_spec.name IS 'Unique studio name for the loaded spec; independent of the OpenAPI info.title';

CREATE TABLE IF NOT EXISTS td_test_case (
    test_case_id           VARCHAR(36)   PRIMARY KEY,
    spec_id                VARCHAR(36)   NOT NULL REFERENCES td_spec (spec_id) ON DELETE CASCADE,
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
    updated_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE td_test_case IS 'Studio test cases created from OpenAPI operation schemas';

CREATE TABLE IF NOT EXISTS td_test_plan (
    plan_id      VARCHAR(36)   PRIMARY KEY,
    spec_id      VARCHAR(36)   NOT NULL REFERENCES td_spec (spec_id) ON DELETE CASCADE,
    name         VARCHAR(255)  NOT NULL,
    description  TEXT,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE td_test_plan IS 'Named test plans for a loaded OpenAPI spec; cases and Excel export are scoped to a plan';

ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS plan_id VARCHAR(36);

ALTER TABLE td_spec ADD COLUMN IF NOT EXISTS name VARCHAR(255);

UPDATE td_spec
   SET name = COALESCE(NULLIF(BTRIM(name), ''), NULLIF(BTRIM(title), ''), spec_id)
 WHERE name IS NULL OR BTRIM(name) = '';

CREATE TABLE IF NOT EXISTS td_test_case_param (
    test_case_id  VARCHAR(36)  NOT NULL REFERENCES td_test_case (test_case_id) ON DELETE CASCADE,
    param_in      VARCHAR(16)  NOT NULL CHECK (param_in IN ('path', 'query', 'header', 'cookie')),
    param_name    VARCHAR(255) NOT NULL,
    param_value   TEXT,
    PRIMARY KEY (test_case_id, param_in, param_name)
);

COMMENT ON TABLE td_test_case_param IS 'Path, query, header, and cookie values for a test case';

ALTER TABLE td_test_case_param DROP CONSTRAINT IF EXISTS td_test_case_param_param_in_check;
ALTER TABLE td_test_case_param ADD CONSTRAINT td_test_case_param_param_in_check
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
    test_case_id  VARCHAR(36)   NOT NULL REFERENCES td_test_case (test_case_id) ON DELETE CASCADE,
    file_name     VARCHAR(512)  NOT NULL,
    content_type  VARCHAR(255),
    size_bytes    BIGINT        NOT NULL,
    content       BYTEA         NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (file_id)
);

ALTER TABLE td_request_file ADD COLUMN IF NOT EXISTS test_case_id VARCHAR(36);
DELETE FROM td_request_file WHERE test_case_id IS NULL;
ALTER TABLE td_request_file DROP CONSTRAINT IF EXISTS fk_td_request_file_spec;
ALTER TABLE td_request_file DROP CONSTRAINT IF EXISTS td_request_file_spec_id_fkey;
DROP INDEX IF EXISTS ix_td_request_file_spec;
ALTER TABLE td_request_file DROP COLUMN IF EXISTS spec_id;
ALTER TABLE td_request_file ALTER COLUMN test_case_id SET NOT NULL;

COMMENT ON TABLE td_request_file IS 'Uploaded multipart request files owned by a test case';

CREATE INDEX IF NOT EXISTS ix_td_request_file_case
    ON td_request_file (test_case_id);
