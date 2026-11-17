-- Same as db/migration/V9__blocked_outcome.sql (MySQL check form).
ALTER TABLE td_run_result DROP CONSTRAINT ck_td_run_result_outcome;
ALTER TABLE td_run_result ADD CONSTRAINT ck_td_run_result_outcome CHECK (outcome IN ('passed', 'failed', 'skipped', 'error', 'blocked'));
