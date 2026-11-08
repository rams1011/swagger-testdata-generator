-- Shared settings: same table as db/migration/V6__collection_settings.sql, in MySQL types.
CREATE TABLE td_collection_settings (
    spec_id        VARCHAR(36)  NOT NULL,
    document_json  LONGTEXT     NOT NULL,
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_td_collection_settings PRIMARY KEY (spec_id),
    CONSTRAINT fk_td_collection_settings_spec FOREIGN KEY (spec_id)
        REFERENCES td_spec (spec_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
