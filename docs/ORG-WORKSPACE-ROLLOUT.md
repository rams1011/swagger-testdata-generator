# Organization / workspace rollout plan

Implements [ADR-001](./ADR-001-organization-workspace-access.md). Work in order; each phase should ship with tests.

## Phase 0 — Data repair (Flyway V5)

**Goal:** Every org member has workspace membership for every workspace in that org.

- Apply `V5__org_workspace_membership_sync.sql` (PostgreSQL/H2 and MySQL copies).
- Verify on staging:

```sql
-- Should return 0 rows after V5
SELECT om.org_id, om.account_id, t.tenant_id
  FROM td_org_membership om
  JOIN td_tenant t ON t.org_id = om.org_id
 WHERE NOT EXISTS (
       SELECT 1 FROM td_membership m
        WHERE m.tenant_id = t.tenant_id AND m.account_id = om.account_id
 );
```

**Files:** `db/migration/V5__*.sql`, `db/migration_mysql/V5__*.sql`

---

## Phase 1 — API provisioning (single service method)

**Goal:** New code paths cannot create org/workspace drift.

Add to `AuthService` (or `MembershipProvisioner`):

```text
provisionWorkspaceMembershipsForOrgMember(orgId, accountId, defaultRole)
provisionAllOrgMembersOnWorkspace(workspaceId, orgId, defaultRoleForOthers)
removeWorkspaceMembershipsForOrg(orgId, accountId)  // on org remove
```

Call sites:

| Method | Change |
|--------|--------|
| `addMember` (workspace) | After save, unchanged; already adds org `MEMBER` if missing |
| **New** `addOrgMember` | Seat check → `saveOrgMembership` → `provisionWorkspaceMembershipsForOrgMember(..., MEMBER)` |
| `createWorkspace` | After creator `OWNER`, call `provisionAllOrgMembersOnWorkspace` (others → `MEMBER`) |
| `register` | Optional: call provisioner for consistency (already has both rows for first workspace) |
| LDAP first login | When org/workspace bootstrap runs, use same provisioner |

**Tests:**

- Org with 2 workspaces, add org member → 2 `td_membership` rows.
- Create 3rd workspace → all org members get membership.
- Seat limit still enforced only on org add.

**Files:** `AuthService.java`, `TenantStore.java`, `JdbcTenantStore.java`, new unit tests beside `AuthServiceLdapTest`.

---

## Phase 2 — Org REST API

**Status:** Implemented (API + session `organization.role`).

**Goal:** Primary admin flow is org-scoped.

Add to `AuthController`:

| Method | Path | Auth |
|--------|------|------|
| GET | `/api/organizations/current/members` | Org `OWNER`/`ADMIN` or workspace manager (transition) |
| POST | `/api/organizations/current/members` | Org admin; body like `AddMemberRequest` |
| PUT | `/api/organizations/current/members/{accountId}` | Org role |
| DELETE | `/api/organizations/current/members/{accountId}` | Remove org + all workspace memberships under org |
| GET | `/api/organizations/current/workspaces` | Any org member |

Resolve “current organization” from `TenantContext` → current workspace → `org_id`.

Extend `OrganizationView` → `(id, slug, name, role)`.

Extend `AuthSessionView.toView` to set `organization.role` from `findOrgRole`.

**Tests:** MVC tests on `AuthController`; isolation test updated for org sibling workspaces.

---

## Phase 3 — Compatibility aliases

**Goal:** UI and integrators can migrate names without breaking old clients.

| Add | Keep |
|-----|------|
| `POST /api/auth/switch-workspace` `{ "workspaceId" }` | `switch-tenant` + `tenantId` |
| Header `X-Workspace-Id` | `X-Tenant-Id` in `TenantFilter` |
| Session JSON `workspace`, `workspaces` | `tenant`, `tenants` |

Document deprecation in README (remove in 2.x).

---

## Phase 4 — UI

**Status:** Implemented.

**Goal:** Match ADR flows; fix datacenter admin gap.

| Task | File(s) |
|------|---------|
| Remove `session.saas === false` redirect from Admin | `AdminPage.jsx` |
| Gate Admin on `organization.role` or workspace manager | `session.js` helpers |
| New **Organization → People** route (or split Admin) | New page + `api.ts` org endpoints |
| Workspace admin copy: “Adjust access in this workspace” | `AdminPage.jsx`, `GuidePage.jsx` |
| Optional: show org name in home sidebar | `HomeNav.jsx` |
| Types for `organization.role`, `workspace` aliases | `studio-api.ts` |

**Tests:** Vitest for session helpers; smoke: datacenter admin can open `/admin`.

---

## Phase 5 — Docs and operator comms

- Update `testdata-studio-ui/docs/DEPLOYMENT.md` security table (org provision policy).
- Update `DATA_STUDIO_DESIGN.md` §4 logical model diagram.
- Swagger README: endpoint table for org routes.

---

## Rollback

- **V5** is additive (inserts memberships only). Rollback = restore DB backup if provisioned roles were wrong.
- **Phase 1+** code: feature flag `testdata.auth.org-provision-workspaces=true` (default true) to disable auto-provision without revert.

Suggested property in `StudioProperties`:

```yaml
testdata:
  auth:
    org-provision-workspaces: true
```

---

## Definition of done

- [ ] V5 applied; verification SQL returns 0 gaps.
- [ ] `addOrgMember` + create workspace provision all memberships.
- [x] Org API CRUD + session `organization.role`.
- [x] UI org people page; datacenter Admin works.
- [ ] `TenantIsolationTest` + new org provisioning tests green in CI.
