ALTER TABLE td_run ADD COLUMN environment_id VARCHAR(36);

CREATE INDEX ix_td_run_spec_plan_env
    ON td_run (spec_id, plan_id, environment_id, started_at);
