-- Data-driven runs: same column as db/migration/V5__case_data_rows.sql, in MySQL types.
ALTER TABLE td_test_case ADD COLUMN data_rows LONGTEXT NULL;
