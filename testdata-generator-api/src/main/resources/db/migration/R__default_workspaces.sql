-- Default workspaces every edition expects: Local (local edition) and SaaS (platform workspace).
-- Repeatable migration: Flyway runs it after the versioned ones, and again only if this file changes.
-- Each insert is skipped when the row exists, so edits here never duplicate or overwrite data.

INSERT INTO td_organization (org_id, slug, name, created_at)
SELECT '00000000-0000-0000-0000-000000000001', 'local', 'Local', CURRENT_TIMESTAMP
 WHERE NOT EXISTS (SELECT 1 FROM td_organization WHERE org_id = '00000000-0000-0000-0000-000000000001');

INSERT INTO td_organization (org_id, slug, name, created_at)
SELECT '00000000-0000-0000-0000-000000000002', 'saas-platform', 'SaaS', CURRENT_TIMESTAMP
 WHERE NOT EXISTS (SELECT 1 FROM td_organization WHERE org_id = '00000000-0000-0000-0000-000000000002');

INSERT INTO td_tenant (tenant_id, slug, name, ready, created_at, org_id)
SELECT '00000000-0000-0000-0000-000000000001', 'local', 'Local', TRUE, CURRENT_TIMESTAMP,
       '00000000-0000-0000-0000-000000000001'
 WHERE NOT EXISTS (SELECT 1 FROM td_tenant WHERE tenant_id = '00000000-0000-0000-0000-000000000001');

INSERT INTO td_tenant (tenant_id, slug, name, ready, created_at, org_id)
SELECT '00000000-0000-0000-0000-000000000002', 'saas-platform', 'SaaS', TRUE, CURRENT_TIMESTAMP,
       '00000000-0000-0000-0000-000000000002'
 WHERE NOT EXISTS (SELECT 1 FROM td_tenant WHERE tenant_id = '00000000-0000-0000-0000-000000000002');

INSERT INTO td_subscription (subscription_id, org_id, plan_code, status,
                             seat_limit, workspace_limit, spec_limit, run_limit_month, created_at)
SELECT '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'enterprise', 'active',
       10, 5, 50, 5000, CURRENT_TIMESTAMP
 WHERE NOT EXISTS (SELECT 1 FROM td_subscription WHERE org_id = '00000000-0000-0000-0000-000000000001');

INSERT INTO td_subscription (subscription_id, org_id, plan_code, status,
                             seat_limit, workspace_limit, spec_limit, run_limit_month, created_at)
SELECT '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002', 'enterprise', 'active',
       10, 5, 50, 5000, CURRENT_TIMESTAMP
 WHERE NOT EXISTS (SELECT 1 FROM td_subscription WHERE org_id = '00000000-0000-0000-0000-000000000002');
