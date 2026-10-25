# ADR-001: Organization and workspace access model

**Status:** Accepted (implementation in progress)  
**Date:** 2026-03-22  
**Scope:** `testdata-generator-api`, `testdata-studio-ui`

## Context

Studio persists:

| Table | Purpose today |
|-------|----------------|
| `td_organization` | Billing entity (SaaS), named org |
| `td_org_membership` | Org roster; **seats** counted here |
| `td_tenant` | Workspace (product: “workspace”); `org_id` FK |
| `td_membership` | **Only** gate used for catalog APIs (`TenantFilter`) |
| `td_session` | Active workspace (`tenant_id`) per login |

Behavior today is **workspace-first**: `listTenantsForAccount` joins `td_membership` only. Adding someone on **Workspace admin** creates org + workspace rows, but org members do **not** automatically see sibling workspaces. That feels inconsistent with org-level seats and subscription copy (“organization owns the plan”).

## Decision

### 1. Terminology (product + new API fields)

| Concept | Canonical name | Legacy (compat) |
|---------|----------------|-----------------|
| Person | **Account** | — |
| Billing / company | **Organization** | — |
| Isolated catalog | **Workspace** | `tenant`, `tenantId` |
| Active catalog | **Current workspace** | session `tenant` |

Keep DB columns `tenant_id` until a major version; add JSON aliases in session responses (`workspace`, `workspaces`).

### 2. Two memberships, one job each

**Organization membership (`td_org_membership`)**

- **Job:** Roster, **seat limit**, org-admin actions, list workspaces in org.
- **Roles:** `OWNER`, `ADMIN`, `MEMBER` (same strings as today for org rows).
- **Org admin:** `OWNER` or `ADMIN` on org (new checks; today only workspace admins exist in UI).

**Workspace membership (`td_membership`)**

- **Job:** Authorize every `/api/specs`, `/api/testdata`, `/api/runs`, … request.
- **Roles:** `OWNER`, `ADMIN`, `MEMBER` (unchanged).

**Hard rule:** Catalog code never checks org membership alone. Org membership triggers **provisioning** of workspace membership (below).

### 3. Default access policy (chosen)

**Org roster first, workspace grant second**

When account `A` is in `td_org_membership` for org `O`:

1. **Organization → People** adds `A` to the roster (seat). No `td_membership` rows are created automatically.
2. **Workspace access** grants `td_membership(W, A)` with a workspace role; only org members can be granted.
3. **Removing** org membership removes all workspace memberships under that org for that account.
4. **Removing** workspace access only deletes that workspace row; org membership (seat) remains.

**Create workspace:** Creator gets `OWNER` on the new workspace. Other org members are **not** auto-granted (use Workspace access).

**Register / bootstrap:** Unchanged intent: org owner + first workspace owner = same account.

### 4. Seats and limits

- **Seat** = distinct account in `td_org_membership` for the org (already implemented in `EntitlementService.requireSeat` / `countOrgMembers`).
- **Workspace limit** = `COUNT(td_tenant WHERE org_id = ?)` (unchanged).
- Adding org member runs seat check **once**; provisioning workspace memberships does not consume extra seats.

### 5. Session shape (target)

`GET /api/auth/session` and login responses add (non-breaking):

```json
{
  "organization": {
    "id": "...",
    "slug": "...",
    "name": "...",
    "role": "OWNER"
  },
  "workspace": { "id": "...", "name": "...", "role": "ADMIN", "ready": true },
  "workspaces": [ ... ],
  "tenant": { ... },
  "tenants": [ ... ]
}
```

`organization.role` from `td_org_membership`. Keep `tenant` / `tenants` until UI migration completes.

### 6. HTTP API (target)

| Phase | Endpoint | Notes |
|-------|----------|--------|
| 1 | Existing `/api/tenants/current/members` | Becomes “workspace members”; behavior unchanged |
| 2 | `GET/POST/PUT/DELETE /api/organizations/current/members` | Org Owner/Admin only. POST does not grant workspace access. |
| 2 | `GET /api/organizations/current/workspaces` | All workspaces in org (for admin UI) |
| 3 | `POST /api/auth/switch-workspace` | Alias of `switch-tenant`; old path kept |
| 3 | Request header `X-Workspace-Id` | Alias of `X-Tenant-Id` |

Datacenter: same org/workspace model; billing endpoints remain SaaS-only.

### 7. UI (target)

| Page | Scope |
|------|--------|
| **Organization → People** | Org members, seats, add/remove (primary invite path) |
| **Workspace → Access** | Per-workspace role overrides (optional tighten/loosen) |
| **Admin** | Available when `loginRequired` and user is org or workspace manager — **not** gated on `session.saas` |

### 8. Platform admin

`platform_role = SAAS_ADMIN` unchanged: operator subscriptions only, no customer catalog API.

## Consequences

**Positive**

- Org switcher lists all workspaces user should see (after backfill + provisioning).
- Seats align with “people in the company.”
- Clear story for datacenter and SaaS.

**Negative / migration**

- One-time backfill for org members missing workspace rows (Flyway `V5`).
- Users who relied on “hidden” workspaces lose isolation unless org membership is removed.
- Dual API names until deprecation window ends.

## Alternatives considered

1. **Workspace-only ACL (status quo)** — Rejected: conflicts with org-level seats and UX copy.
2. **Org membership = full access without `td_membership` rows** — Rejected: would require rewriting `TenantFilter` and every query guard.
3. **Shared catalog across workspaces** — Out of scope; explicit future ADR if needed.

## References

- Schema: `testdata-generator-api/src/main/resources/db/migration/V1__studio.sql`
- Auth: `AuthService`, `JdbcTenantStore.listTenantsForAccount`, `addMember`, `createWorkspace`
- Rollout: [ORG-WORKSPACE-ROLLOUT.md](./ORG-WORKSPACE-ROLLOUT.md)
