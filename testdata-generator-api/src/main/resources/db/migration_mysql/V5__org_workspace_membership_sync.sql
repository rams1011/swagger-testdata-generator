-- ADR-001 (MySQL 8.4+). Same as db/migration/V5__org_workspace_membership_sync.sql.

INSERT INTO td_membership (tenant_id, account_id, role, created_at)
SELECT t.tenant_id,
       om.account_id,
       CASE
         WHEN om.role IN ('OWNER', 'ADMIN') THEN 'ADMIN'
         ELSE 'MEMBER'
       END,
       CURRENT_TIMESTAMP
  FROM td_org_membership om
  JOIN td_tenant t ON t.org_id = om.org_id
 WHERE NOT EXISTS (
       SELECT 1
         FROM td_membership m
        WHERE m.tenant_id = t.tenant_id
          AND m.account_id = om.account_id
 );
