-- Collection variables: one JSON document of rows (key, value, secret) per collection, like td_global_variables per
-- workspace. Kept out of td_spec so saving them does not change the collection revision. MySQL: db/migration_mysql.
CREATE TABLE td_collection_variables (
    spec_id        VARCHAR(36)  NOT NULL,
    document_json  TEXT         NOT NULL,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_collection_variables PRIMARY KEY (spec_id),
    CONSTRAINT fk_td_collection_variables_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
);
