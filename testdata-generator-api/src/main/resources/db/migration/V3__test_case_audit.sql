ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS created_by_account_id VARCHAR(36);
ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS created_by_label VARCHAR(320);
ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS updated_by_account_id VARCHAR(36);
ALTER TABLE td_test_case ADD COLUMN IF NOT EXISTS updated_by_label VARCHAR(320);
