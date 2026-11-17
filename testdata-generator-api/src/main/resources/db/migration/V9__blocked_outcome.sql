-- Production safety: a request the safety check stopped is stored as its own outcome. Keep migration_mysql/V9 in step.
ALTER TABLE td_run_result DROP CONSTRAINT ck_td_run_result_outcome;
ALTER TABLE td_run_result ADD CONSTRAINT ck_td_run_result_outcome CHECK (
    CASE outcome WHEN 'passed' THEN TRUE WHEN 'failed' THEN TRUE WHEN 'skipped' THEN TRUE WHEN 'error' THEN TRUE
                 WHEN 'blocked' THEN TRUE ELSE FALSE END);
