-- Every table under a collection names its workspace, and its keys include it, so a row can't hang under another
-- workspace's collection, plan, case, environment or run. On PostgreSQL, V12 adds row-level security on the column.
-- Run history's optional references (plan, environment, case) stay single-column keys: ON DELETE SET NULL on a key
-- with tenant_id would clear tenant_id too.

-- ---------- The workspace of each row, taken from its parent ----------
ALTER TABLE td_test_plan ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_test_plan SET tenant_id = (SELECT p.tenant_id FROM td_spec p WHERE p.spec_id = td_test_plan.spec_id);
ALTER TABLE td_test_plan ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE td_environment ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_environment SET tenant_id = (SELECT p.tenant_id FROM td_spec p WHERE p.spec_id = td_environment.spec_id);
ALTER TABLE td_environment ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE td_collection_variables ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_collection_variables SET tenant_id = (SELECT p.tenant_id FROM td_spec p WHERE p.spec_id = td_collection_variables.spec_id);
ALTER TABLE td_collection_variables ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE td_collection_settings ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_collection_settings SET tenant_id = (SELECT p.tenant_id FROM td_spec p WHERE p.spec_id = td_collection_settings.spec_id);
ALTER TABLE td_collection_settings ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE td_test_case ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_test_case SET tenant_id = (SELECT p.tenant_id FROM td_spec p WHERE p.spec_id = td_test_case.spec_id);
ALTER TABLE td_test_case ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE td_test_case_param ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_test_case_param SET tenant_id = (SELECT p.tenant_id FROM td_test_case p WHERE p.test_case_id = td_test_case_param.test_case_id);
ALTER TABLE td_test_case_param ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE td_request_file ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_request_file SET tenant_id = (SELECT p.tenant_id FROM td_test_case p WHERE p.test_case_id = td_request_file.test_case_id);
ALTER TABLE td_request_file ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE td_test_case_draft ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_test_case_draft SET tenant_id = (SELECT p.tenant_id FROM td_test_case p WHERE p.test_case_id = td_test_case_draft.test_case_id);
ALTER TABLE td_test_case_draft ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE td_test_case_version ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_test_case_version SET tenant_id = (SELECT p.tenant_id FROM td_test_case p WHERE p.test_case_id = td_test_case_version.test_case_id);
ALTER TABLE td_test_case_version ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE td_test_case_environment ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_test_case_environment SET tenant_id = (SELECT p.tenant_id FROM td_test_case p WHERE p.test_case_id = td_test_case_environment.test_case_id);
ALTER TABLE td_test_case_environment ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE td_run ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_run SET tenant_id = (SELECT p.tenant_id FROM td_spec p WHERE p.spec_id = td_run.spec_id);
ALTER TABLE td_run ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE td_run_result ADD COLUMN tenant_id VARCHAR(36);
UPDATE td_run_result SET tenant_id = (SELECT p.tenant_id FROM td_run p WHERE p.run_id = td_run_result.run_id);
ALTER TABLE td_run_result ALTER COLUMN tenant_id SET NOT NULL;

-- ---------- What the composite keys refer to ----------
ALTER TABLE td_spec ADD CONSTRAINT uq_td_spec_tenant UNIQUE (spec_id, tenant_id);
ALTER TABLE td_test_plan ADD CONSTRAINT uq_td_test_plan_tenant UNIQUE (plan_id, tenant_id);
ALTER TABLE td_test_case ADD CONSTRAINT uq_td_test_case_tenant UNIQUE (test_case_id, tenant_id);
ALTER TABLE td_environment ADD CONSTRAINT uq_td_environment_tenant UNIQUE (environment_id, tenant_id);
ALTER TABLE td_run ADD CONSTRAINT uq_td_run_tenant UNIQUE (run_id, tenant_id);

-- ---------- Composite keys in place of the single-column ones ----------
ALTER TABLE td_test_plan DROP CONSTRAINT fk_td_test_plan_spec;
ALTER TABLE td_test_plan ADD CONSTRAINT fk_td_test_plan_spec_tenant FOREIGN KEY (spec_id, tenant_id)
    REFERENCES td_spec (spec_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_environment DROP CONSTRAINT fk_td_environment_spec;
ALTER TABLE td_environment ADD CONSTRAINT fk_td_environment_spec_tenant FOREIGN KEY (spec_id, tenant_id)
    REFERENCES td_spec (spec_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_collection_variables DROP CONSTRAINT fk_td_collection_variables_spec;
ALTER TABLE td_collection_variables ADD CONSTRAINT fk_td_collection_variables_spec_tenant FOREIGN KEY (spec_id, tenant_id)
    REFERENCES td_spec (spec_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_collection_settings DROP CONSTRAINT fk_td_collection_settings_spec;
ALTER TABLE td_collection_settings ADD CONSTRAINT fk_td_collection_settings_spec_tenant FOREIGN KEY (spec_id, tenant_id)
    REFERENCES td_spec (spec_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_test_case DROP CONSTRAINT fk_td_test_case_spec;
ALTER TABLE td_test_case ADD CONSTRAINT fk_td_test_case_spec_tenant FOREIGN KEY (spec_id, tenant_id)
    REFERENCES td_spec (spec_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_test_case DROP CONSTRAINT fk_td_test_case_plan;
ALTER TABLE td_test_case ADD CONSTRAINT fk_td_test_case_plan_tenant FOREIGN KEY (plan_id, tenant_id)
    REFERENCES td_test_plan (plan_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_test_case_param DROP CONSTRAINT fk_td_test_case_param_case;
ALTER TABLE td_test_case_param ADD CONSTRAINT fk_td_test_case_param_case_tenant FOREIGN KEY (test_case_id, tenant_id)
    REFERENCES td_test_case (test_case_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_request_file DROP CONSTRAINT fk_td_request_file_case;
ALTER TABLE td_request_file ADD CONSTRAINT fk_td_request_file_case_tenant FOREIGN KEY (test_case_id, tenant_id)
    REFERENCES td_test_case (test_case_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_test_case_draft DROP CONSTRAINT fk_td_test_case_draft_case;
ALTER TABLE td_test_case_draft ADD CONSTRAINT fk_td_test_case_draft_case_tenant FOREIGN KEY (test_case_id, tenant_id)
    REFERENCES td_test_case (test_case_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_test_case_version DROP CONSTRAINT fk_td_test_case_version_case;
ALTER TABLE td_test_case_version ADD CONSTRAINT fk_td_test_case_version_case_tenant FOREIGN KEY (test_case_id, tenant_id)
    REFERENCES td_test_case (test_case_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_test_case_environment DROP CONSTRAINT fk_td_tce_case;
ALTER TABLE td_test_case_environment ADD CONSTRAINT fk_td_tce_case_tenant FOREIGN KEY (test_case_id, tenant_id)
    REFERENCES td_test_case (test_case_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_test_case_environment DROP CONSTRAINT fk_td_tce_environment;
ALTER TABLE td_test_case_environment ADD CONSTRAINT fk_td_tce_environment_tenant FOREIGN KEY (environment_id, tenant_id)
    REFERENCES td_environment (environment_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_run DROP CONSTRAINT fk_td_run_spec;
ALTER TABLE td_run ADD CONSTRAINT fk_td_run_spec_tenant FOREIGN KEY (spec_id, tenant_id)
    REFERENCES td_spec (spec_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_run_result DROP CONSTRAINT fk_td_run_result_run;
ALTER TABLE td_run_result ADD CONSTRAINT fk_td_run_result_run_tenant FOREIGN KEY (run_id, tenant_id)
    REFERENCES td_run (run_id, tenant_id) ON DELETE CASCADE;
ALTER TABLE td_run_result DROP CONSTRAINT fk_td_run_result_spec;
ALTER TABLE td_run_result ADD CONSTRAINT fk_td_run_result_spec_tenant FOREIGN KEY (spec_id, tenant_id)
    REFERENCES td_spec (spec_id, tenant_id) ON DELETE CASCADE;
