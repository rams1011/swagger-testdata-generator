-- Data-driven runs: a case may hold rows of data (a JSON array of objects); Run sends it once per row.
-- MySQL: db/migration_mysql/V5__case_data_rows.sql.
ALTER TABLE td_test_case ADD COLUMN data_rows TEXT;
