-- Shared settings: auth and headers a collection and each case group hand down to cases, one JSON document per
-- collection. Kept out of td_spec so saving them leaves the collection revision alone. MySQL: db/migration_mysql.
CREATE TABLE td_collection_settings (
    spec_id        VARCHAR(36)  NOT NULL,
    document_json  TEXT         NOT NULL,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_td_collection_settings PRIMARY KEY (spec_id),
    CONSTRAINT fk_td_collection_settings_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
);
