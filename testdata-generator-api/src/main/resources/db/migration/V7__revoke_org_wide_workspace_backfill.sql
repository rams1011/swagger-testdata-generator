-- V5 inserted a workspace membership for every org member on every workspace,
-- all in one statement (same created_at). Explicit grants are single rows with
-- their own timestamp. Workspace OWNER rows are kept.

DELETE FROM td_membership
 WHERE role <> 'OWNER'
   AND created_at IN (
       SELECT created_at FROM (
           SELECT created_at
             FROM td_membership
            GROUP BY created_at
           HAVING COUNT(*) > 1
       ) bulk_times
   )
   AND EXISTS (
       SELECT 1
         FROM td_org_membership om
         JOIN td_tenant t ON t.tenant_id = td_membership.tenant_id
        WHERE om.account_id = td_membership.account_id
          AND om.org_id = t.org_id
          AND (
               (om.role IN ('OWNER', 'ADMIN') AND td_membership.role = 'ADMIN')
            OR (om.role = 'MEMBER' AND td_membership.role = 'MEMBER')
          )
   );
